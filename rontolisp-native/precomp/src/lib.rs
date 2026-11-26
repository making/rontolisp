//! The precompile shim: `.wasm` bytes in, wasmtime-precompiled (Cranelift) module bytes
//! out, behind a C ABI the Java side calls through FFM.
//!
//! ```c
//! int32_t     rl_precompile(const uint8_t *wasm, size_t len, const char *platform, const char *cpu,
//!                           uint8_t **out, size_t *out_len);
//! int32_t     rl_assemble(const uint8_t *stub, size_t stub_len, const uint8_t *module,
//!                         size_t module_len, uint8_t **out, size_t *out_len);
//! void        rl_free(uint8_t *p, size_t len);
//! const char *rl_version(void);   /* static, NUL-terminated; never freed */
//! ```
//!
//! `platform` is the platform the output runs on (`linux-x86_64`, `macos-aarch64`, ...;
//! [`PLATFORMS`]), `cpu` the CPU features its code may use ([`Cpu`]); both are
//! NUL-terminated. `rl_precompile` returns 0 with the module in `*out`, or non-zero with a
//! UTF-8 error message there; `rl_assemble` likewise with the executable that runs `module`
//! under `stub` ([`assemble`]). Either buffer is released with `rl_free(*out, *out_len)`.
//! A panic is caught at the boundary (this library lives inside a JVM) and reported as an
//! error.

use std::ffi::{CStr, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::slice;

use wasmtime::Config;

pub mod macho;

/// A platform an output can run on: the name the Java side gives its resource directory,
/// the triple Cranelift compiles for, and what [`Cpu::Baseline`] assumes of its CPU.
#[derive(Debug, PartialEq, Eq)]
pub struct Platform {
    pub name: &'static str,
    pub triple: &'static str,
    /// Cranelift ISA presets and flags every CPU of the platform has.
    pub baseline: &'static [&'static str],
    /// Levels `--native-cpu` may name beyond `baseline` and `host`.
    pub levels: &'static [&'static str],
}

const X86_64_LEVELS: &[&str] = &["x86-64-v2", "x86-64-v3", "x86-64-v4"];

/// Every platform the shim precompiles for (the backends this build of Cranelift carries
/// permitting).
///
/// - x86_64: no extension beyond SSE2, the x86-64 psABI floor (every x86_64 CPU and
///   emulator). The wasm-GC backend's code gains nothing measurable from more
///   (`.kb/native-output.md`, "CPU baseline").
/// - Linux aarch64: Armv8.0, the AArch64 floor. The optional features Cranelift knows
///   (LSE atomics, FP16, dot product, I8MM) only change atomics and SIMD instructions the
///   backend does not emit without `--simd`.
/// - macOS aarch64: the Apple M1, the oldest Apple silicon, with what Cranelift's host
///   detection enables on it (return addresses signed with the B key, as macOS requires).
pub const PLATFORMS: &[Platform] = &[
    Platform {
        name: "linux-x86_64",
        triple: "x86_64-unknown-linux-gnu",
        baseline: &[],
        levels: X86_64_LEVELS,
    },
    Platform {
        name: "linux-aarch64",
        triple: "aarch64-unknown-linux-gnu",
        baseline: &[],
        levels: &[],
    },
    Platform {
        name: "macos-aarch64",
        triple: "aarch64-apple-darwin",
        baseline: &[
            "has_lse",
            "has_pauth",
            "has_fp16",
            "has_dotprod",
            "sign_return_address",
            "sign_return_address_with_bkey",
        ],
        levels: &[],
    },
    Platform {
        name: "macos-x86_64",
        triple: "x86_64-apple-darwin",
        baseline: &[],
        levels: X86_64_LEVELS,
    },
];

/// The platform this shim runs on, if it is one of [`PLATFORMS`].
pub fn host_platform() -> Option<&'static Platform> {
    let os = match std::env::consts::OS {
        "linux" => "linux",
        "macos" => "macos",
        _ => return None,
    };
    let name = format!("{os}-{}", std::env::consts::ARCH);
    PLATFORMS.iter().find(|p| p.name == name)
}

/// The platform named `name`.
pub fn platform(name: &str) -> wasmtime::Result<&'static Platform> {
    PLATFORMS.iter().find(|p| p.name == name).ok_or_else(|| {
        let known: Vec<&str> = PLATFORMS.iter().map(|p| p.name).collect();
        wasmtime::format_err!("unknown platform '{name}' (known: {})", known.join(", "))
    })
}

/// What the precompiled code may assume of the CPU it runs on.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cpu<'a> {
    /// The platform's [`Platform::baseline`]: runs on every CPU of it.
    Baseline,
    /// Every feature of the CPU precompiling (Cranelift's host detection): the output may
    /// be refused by an older CPU of the same platform. Host platform only.
    Host,
    /// A named level of [`Platform::levels`] (`x86-64-v3`, ...).
    Level(&'a str),
}

impl<'a> Cpu<'a> {
    /// `baseline`, `host` or a level name.
    pub fn parse(name: &'a str) -> Self {
        match name {
            "baseline" => Cpu::Baseline,
            "host" => Cpu::Host,
            level => Cpu::Level(level),
        }
    }
}

/// The engine configuration that precompiles for `platform` and `cpu`: [`rlabi::config`]
/// (what the stub loads under) plus the target triple and the CPU features. Only the
/// compiler half differs, so the stub needs no knowledge of either: wasmtime checks the
/// module's triple and each enabled CPU feature against the running host when it loads.
pub fn config_for(platform: &Platform, cpu: Cpu) -> wasmtime::Result<Config> {
    let mut c = rlabi::config();
    let flags: &[&str] = match cpu {
        Cpu::Host => {
            if host_platform() != Some(platform) {
                wasmtime::bail!(
                    "--native-cpu=host describes this machine, a {}; it cannot target {}",
                    host_platform().map_or("platform without a --native build", |p| p.name),
                    platform.name
                );
            }
            // No explicit target: wasmtime then infers the host's CPU features.
            return Ok(c);
        }
        Cpu::Baseline => platform.baseline,
        Cpu::Level(level) => {
            let Some(level) = platform.levels.iter().find(|l| **l == level) else {
                let mut known = vec!["baseline", "host"];
                known.extend(platform.levels);
                wasmtime::bail!(
                    "unknown --native-cpu '{level}' for {} (known: {})",
                    platform.name,
                    known.join(", ")
                );
            };
            std::slice::from_ref(level)
        }
    };
    // An explicit target turns Cranelift's host detection off, even for the host's triple.
    c.target(platform.triple)?;
    for flag in flags {
        // SAFETY: the flags are ISA features; wasmtime refuses the module at load time on a
        // CPU that lacks one (`Engine::check_compatible_with_isa_flag`).
        unsafe {
            c.cranelift_flag_enable(flag);
        }
    }
    Ok(c)
}

/// The compile error or the module precompiled for `platform` and `cpu`.
pub fn precompile_for(wasm: &[u8], platform: &Platform, cpu: Cpu) -> wasmtime::Result<Vec<u8>> {
    wasmtime::Engine::new(&config_for(platform, cpu)?)?.precompile_module(wasm)
}

/// The compile error or the module precompiled for the host's platform at its baseline.
pub fn precompile(wasm: &[u8]) -> wasmtime::Result<Vec<u8>> {
    let host = host_platform().ok_or_else(|| wasmtime::format_err!("no --native build for this platform"))?;
    precompile_for(wasm, host, Cpu::Baseline)
}

/// The executable that runs `module` (precompiled for the stub's platform) under the
/// runner `stub`: on macOS the module embedded in the image and the image re-signed
/// ([`macho::embed`]), elsewhere the module appended ([`rlabi::payload::append`]).
pub fn assemble(stub: &[u8], module: &[u8]) -> Result<Vec<u8>, String> {
    if macho::is_macho(stub) {
        macho::embed(stub, module)
    } else {
        Ok(rlabi::payload::append(stub, module))
    }
}

const OK: i32 = 0;
const COMPILE_ERROR: i32 = 1;
const PANIC: i32 = 2;

static VERSION: &str = concat!(rlabi::fingerprint!(), "\0");

fn precompile_c(wasm: &[u8], platform: &CStr, cpu: &CStr) -> wasmtime::Result<Vec<u8>> {
    let platform = platform
        .to_str()
        .map_err(|_| wasmtime::format_err!("the platform name is not UTF-8"))?;
    let cpu = cpu
        .to_str()
        .map_err(|_| wasmtime::format_err!("the CPU name is not UTF-8"))?;
    precompile_for(wasm, self::platform(platform)?, Cpu::parse(cpu))
}

/// `len` bytes at `p`; a null or dangling `p` is fine when `len` is 0.
unsafe fn bytes<'a>(p: *const u8, len: usize) -> &'a [u8] {
    if len == 0 {
        &[][..]
    } else {
        unsafe { slice::from_raw_parts(p, len) }
    }
}

/// Runs `f` behind the C boundary: its result or error message, or the panic it raised,
/// into `*out`; the status code as the result.
unsafe fn answer(
    what: &str,
    out: *mut *mut u8,
    out_len: *mut usize,
    f: impl FnOnce() -> Result<Vec<u8>, String>,
) -> i32 {
    let (code, bytes) = match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(bytes)) => (OK, bytes),
        Ok(Err(e)) => (COMPILE_ERROR, e.into_bytes()),
        Err(panic) => {
            let msg = panic
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".to_string());
            (PANIC, format!("{what}: {msg}").into_bytes())
        }
    };
    let boxed = bytes.into_boxed_slice();
    unsafe {
        *out_len = boxed.len();
        *out = Box::into_raw(boxed).cast::<u8>();
    }
    code
}

/// # Safety
/// `wasm` must point to `len` readable bytes; `platform` and `cpu` must be NUL-terminated
/// strings; `out` and `out_len` must be writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rl_precompile(
    wasm: *const u8,
    len: usize,
    platform: *const c_char,
    cpu: *const c_char,
    out: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let input = unsafe { bytes(wasm, len) };
    let platform = unsafe { CStr::from_ptr(platform) };
    let cpu = unsafe { CStr::from_ptr(cpu) };
    unsafe {
        answer("wasmtime panicked while precompiling", out, out_len, || {
            precompile_c(input, platform, cpu).map_err(|e| format!("{e:?}"))
        })
    }
}

/// # Safety
/// `stub` and `module` must point to `stub_len` and `module_len` readable bytes; `out`
/// and `out_len` must be writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rl_assemble(
    stub: *const u8,
    stub_len: usize,
    module: *const u8,
    module_len: usize,
    out: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let stub = unsafe { bytes(stub, stub_len) };
    let module = unsafe { bytes(module, module_len) };
    unsafe {
        answer("panicked while assembling the executable", out, out_len, || {
            assemble(stub, module)
        })
    }
}

/// # Safety
/// `p` / `len` must be a pair `rl_precompile` or `rl_assemble` returned, released once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rl_free(p: *mut u8, len: usize) {
    if !p.is_null() {
        drop(unsafe { Box::from_raw(std::ptr::slice_from_raw_parts_mut(p, len)) });
    }
}

/// The fingerprint ([`rlabi::FINGERPRINT`]) of the engine this shim precompiles for.
#[unsafe(no_mangle)]
pub extern "C" fn rl_version() -> *const c_char {
    VERSION.as_ptr().cast()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CStr;

    /// `(module)`: the smallest valid module.
    const EMPTY: &[u8] = b"\0asm\x01\0\0\0";

    fn call(wasm: &[u8], platform: &CStr, cpu: &CStr) -> (i32, Vec<u8>) {
        let (mut out, mut out_len) = (std::ptr::null_mut(), 0usize);
        let code = unsafe {
            rl_precompile(
                wasm.as_ptr(),
                wasm.len(),
                platform.as_ptr(),
                cpu.as_ptr(),
                &mut out,
                &mut out_len,
            )
        };
        let bytes = unsafe { slice::from_raw_parts(out, out_len) }.to_vec();
        unsafe { rl_free(out, out_len) };
        (code, bytes)
    }

    fn host() -> &'static CStr {
        match host_platform().expect("tests run on a --native platform").name {
            "linux-x86_64" => c"linux-x86_64",
            "linux-aarch64" => c"linux-aarch64",
            "macos-aarch64" => c"macos-aarch64",
            "macos-x86_64" => c"macos-x86_64",
            other => panic!("{other}"),
        }
    }

    #[test]
    fn version_is_the_shared_fingerprint() {
        let v = unsafe { CStr::from_ptr(rl_version()) };
        assert_eq!(v.to_str().unwrap(), rlabi::FINGERPRINT);
    }

    #[test]
    fn invalid_wasm_comes_back_as_an_error_message() {
        let (code, msg) = call(b"not wasm", host(), c"baseline");
        assert_eq!(code, COMPILE_ERROR);
        assert!(!String::from_utf8(msg).unwrap().is_empty());
    }

    #[test]
    fn empty_input_is_an_error_not_a_crash() {
        let (mut out, mut out_len) = (std::ptr::null_mut(), 0usize);
        let code = unsafe {
            rl_precompile(
                std::ptr::null(),
                0,
                host().as_ptr(),
                c"baseline".as_ptr(),
                &mut out,
                &mut out_len,
            )
        };
        assert_eq!(code, COMPILE_ERROR);
        unsafe { rl_free(out, out_len) };
    }

    #[test]
    fn unknown_platform_and_cpu_are_errors_naming_the_known_ones() {
        let (code, msg) = call(EMPTY, c"windows-x86_64", c"baseline");
        assert_eq!(code, COMPILE_ERROR);
        assert!(String::from_utf8(msg).unwrap().contains("linux-x86_64"));
        let (code, msg) = call(EMPTY, host(), c"pentium");
        assert_eq!(code, COMPILE_ERROR);
        assert!(String::from_utf8(msg).unwrap().contains("baseline, host"));
    }

    #[test]
    fn host_cpu_is_refused_for_another_platform() {
        let other = if host_platform().unwrap().name == "linux-aarch64" {
            platform("linux-x86_64")
        } else {
            platform("linux-aarch64")
        }
        .unwrap();
        let err = config_for(other, Cpu::Host).unwrap_err();
        assert!(format!("{err}").contains("cannot target"), "{err}");
    }

    #[test]
    fn x86_64_levels_are_x86_64_only() {
        assert!(config_for(platform("linux-x86_64").unwrap(), Cpu::Level("x86-64-v3")).is_ok());
        assert!(config_for(platform("linux-aarch64").unwrap(), Cpu::Level("x86-64-v3")).is_err());
    }

    /// Every platform's baseline names flags this Cranelift knows, and every platform's
    /// backend is compiled in: a cross-target output is one precompile away.
    #[test]
    fn every_platform_precompiles_at_every_cpu() {
        for p in PLATFORMS {
            for cpu in [Cpu::Baseline]
                .into_iter()
                .chain(p.levels.iter().map(|l| Cpu::Level(l)))
            {
                let module = precompile_for(EMPTY, p, cpu).unwrap_or_else(|e| panic!("{} {cpu:?}: {e:?}", p.name));
                assert!(
                    wasmtime::Engine::detect_precompiled(&module).is_some(),
                    "{} {cpu:?}: not a wasmtime module",
                    p.name
                );
            }
        }
    }

    fn assemble_c(stub: &[u8], module: &[u8]) -> (i32, Vec<u8>) {
        let (mut out, mut out_len) = (std::ptr::null_mut(), 0usize);
        let code = unsafe {
            rl_assemble(
                stub.as_ptr(),
                stub.len(),
                module.as_ptr(),
                module.len(),
                &mut out,
                &mut out_len,
            )
        };
        let bytes = unsafe { slice::from_raw_parts(out, out_len) }.to_vec();
        unsafe { rl_free(out, out_len) };
        (code, bytes)
    }

    #[test]
    fn rl_assemble_answers_the_executable_or_why_not() {
        assert_eq!(assemble_c(b"STUB", b"m"), (OK, rlabi::payload::append(b"STUB", b"m")));
        let (code, msg) = assemble_c(b"\xcf\xfa\xed\xfe", b"m");
        assert_eq!(code, COMPILE_ERROR);
        assert!(String::from_utf8(msg).unwrap().contains("Mach-O"));
    }
}
