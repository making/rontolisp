//! The runner stub every `--native` output starts with.
//!
//! It finds the precompiled module in its own executable (`rlabi::payload`): on Linux
//! appended to the file, on macOS in the image's own payload section, which the loader has
//! already mapped and the code signature covers. It loads it into an engine built from `rlabi::config` (runtime only: this binary has no
//! compiler), and runs `_start` under WASI Preview 1 with the process's stdio, arguments
//! and environment. The current directory is preopened as fd 3 (what a relative path
//! resolves against) under its absolute name, and the root as `/`, so the program sees the
//! file system a native program would, `..` above the current directory included.
//!
//! Exit status: the program's `proc_exit` code, 0 when `_start` returns, 134 after a trap
//! (what `wasmtime run` answers on Unix), 1 when the module cannot be loaded.

use std::process::exit;

#[cfg(all(
    any(target_arch = "x86_64", target_arch = "aarch64"),
    target_os = "linux",
    any(target_env = "musl", test)
))]
mod memfns;
#[cfg(all(target_os = "macos", target_arch = "aarch64"))]
mod objc;

use rlabi::payload;
use wasmtime::{Engine, Linker, Module, Store, Trap};
use wasmtime_wasi::p1::{self, WasiP1Ctx};
use wasmtime_wasi::{FsPerms, I32Exit, WasiCtxBuilder};

/// `128 + SIGABRT`, the status `wasmtime run` exits with after a trap on Unix.
const TRAP_EXIT: i32 = 134;

/// The section a macOS output's module is embedded in (`rlprecomp::macho`): reserved here
/// with an empty header so that the linker lays out its segment, the last before
/// `__LINKEDIT` (`build.rs` makes it read-only).
#[cfg(target_os = "macos")]
#[used]
#[unsafe(link_section = "__RLPAYLOAD,__payload")]
static PAYLOAD_SECTION: [u8; payload::TRAILER_LEN] = payload::EMPTY_SECTION;

fn main() {
    // Keeps the fingerprint marker in the binary for the side that assembles executables.
    std::hint::black_box(rlabi::STUB_MARKER.as_ptr());
    match run() {
        Ok(()) => exit(0),
        Err(e) => {
            if let Some(code) = e.downcast_ref::<I32Exit>() {
                exit(code.0);
            }
            eprintln!("Error: {e:?}");
            exit(if e.is::<Trap>() { TRAP_EXIT } else { 1 });
        }
    }
}

fn run() -> wasmtime::Result<()> {
    let engine = Engine::new(&rlabi::config())?;
    let module = {
        let image = read_payload()?;
        // SAFETY: the bytes are what rl_precompile produced for this config; wasmtime checks
        // the header, version and engine settings before using them.
        unsafe { Module::deserialize(&engine, image)? }
    };

    let args: Vec<String> = std::env::args_os().map(|a| a.to_string_lossy().into_owned()).collect();
    let mut wasi = WasiCtxBuilder::new();
    wasi.inherit_stdio()
        .inherit_env()
        .args(&args)
        .allow_blocking_current_thread(true);
    wasi.preopened_dir(".", cwd_name(), FsPerms::ReadWrite)
        .map_err(|e| e.context("cannot preopen the current directory"))?;
    wasi.preopened_dir("/", "/", FsPerms::ReadWrite)
        .map_err(|e| e.context("cannot preopen /"))?;

    let mut linker: Linker<WasiP1Ctx> = Linker::new(&engine);
    p1::add_to_linker_sync(&mut linker, |t| t)?;
    // A program using objc: / appkit: imports the Objective-C host (`objc`), which runs
    // it on thread 0 -- this thread.
    let objc = module.imports().any(|i| i.module() == "rlobjc");
    if objc {
        #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
        objc::add_to_linker(&mut linker)?;
        #[cfg(not(all(target_os = "macos", target_arch = "aarch64")))]
        wasmtime::bail!(
            "this program uses the Objective-C runtime (objc:, appkit:), which a --native output reaches on macOS on Apple silicon only"
        );
    }
    let mut store = Store::new(&engine, wasi.build_p1());
    let instance = linker.instantiate(&mut store, &module)?;
    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    if objc {
        objc::bind(&instance, &mut store)?;
    }
    instance
        .get_typed_func::<(), ()>(&mut store, "_start")?
        .call(&mut store, ())
}

/// The guest name of the current directory's preopen: its absolute path, which tells the
/// module where it runs, so a relative path that climbs (`../x`) resolves through the `/`
/// preopen instead of the sandbox of `.` (`_path_dirfd`, `.kb/read-load-streams.md`).
/// `"."` when the path is unknown or not UTF-8: relative paths then cannot climb.
fn cwd_name() -> String {
    std::env::current_dir()
        .ok()
        .and_then(|dir| dir.to_str().map(str::to_owned))
        .unwrap_or_else(|| ".".to_owned())
}

/// The module bytes appended to this executable.
#[cfg(not(target_os = "macos"))]
fn read_payload() -> wasmtime::Result<Vec<u8>> {
    use std::fs::File;
    use std::io::{Read, Seek, SeekFrom};

    // /proc/self/exe is the running image even if its path was replaced or removed.
    let mut file = if cfg!(target_os = "linux") {
        File::open("/proc/self/exe")?
    } else {
        File::open(std::env::current_exe()?)?
    };
    let file_len = file.metadata()?.len();
    if file_len < payload::TRAILER_LEN as u64 {
        wasmtime::bail!("executable too short to carry a module");
    }
    let mut trailer = [0u8; payload::TRAILER_LEN];
    file.seek(SeekFrom::End(-(payload::TRAILER_LEN as i64)))?;
    file.read_exact(&mut trailer)?;
    let (offset, len) = payload::locate(&trailer, file_len).map_err(wasmtime::Error::msg)?;
    let mut image = vec![0u8; len as usize];
    file.seek(SeekFrom::Start(offset))?;
    file.read_exact(&mut image)?;
    Ok(image)
}

/// The module embedded in this image's payload section, as the loader mapped it: no read of
/// the executable file.
#[cfg(target_os = "macos")]
fn read_payload() -> wasmtime::Result<&'static [u8]> {
    use std::ffi::{c_char, c_ulong};

    unsafe extern "C" {
        /// The running executable's Mach-O header (`<mach-o/ldsyms.h>`).
        static _mh_execute_header: u8;
        /// `<mach-o/getsect.h>`: a section's address in the running image (slide applied).
        fn getsectiondata(
            mhp: *const u8,
            segname: *const c_char,
            sectname: *const c_char,
            size: *mut c_ulong,
        ) -> *const u8;
    }
    std::hint::black_box(&PAYLOAD_SECTION);
    let mut size: c_ulong = 0;
    // SAFETY: both names are NUL-terminated; the section, when found, is `size` mapped bytes
    // of this image, which lives as long as the process.
    let section = unsafe {
        let data = getsectiondata(
            &raw const _mh_execute_header,
            c"__RLPAYLOAD".as_ptr(),
            c"__payload".as_ptr(),
            &mut size,
        );
        if data.is_null() {
            wasmtime::bail!(
                "this executable has no {},{} section",
                payload::SEGMENT,
                payload::SECTION
            );
        }
        std::slice::from_raw_parts(data, size as usize)
    };
    payload::embedded(section).map_err(wasmtime::Error::msg)
}
