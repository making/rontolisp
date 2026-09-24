//! The precompile shim: `.wasm` bytes in, wasmtime-precompiled (Cranelift) module bytes
//! out, behind a C ABI the Java side calls through FFM.
//!
//! ```c
//! int32_t     rl_precompile(const uint8_t *wasm, size_t len, uint8_t **out, size_t *out_len);
//! void        rl_free(uint8_t *p, size_t len);
//! const char *rl_version(void);   /* static, NUL-terminated; never freed */
//! ```
//!
//! `rl_precompile` returns 0 with the module in `*out`, or non-zero with a UTF-8 error
//! message there; either buffer is released with `rl_free(*out, *out_len)`. A panic is
//! caught at the boundary (this library lives inside a JVM) and reported as an error.

use std::ffi::c_char;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::slice;

/// The compile error or the precompiled module.
pub fn precompile(wasm: &[u8]) -> wasmtime::Result<Vec<u8>> {
    wasmtime::Engine::new(&rlabi::config())?.precompile_module(wasm)
}

const OK: i32 = 0;
const COMPILE_ERROR: i32 = 1;
const PANIC: i32 = 2;

static VERSION: &str = concat!(rlabi::fingerprint!(), "\0");

/// # Safety
/// `wasm` must point to `len` readable bytes; `out` and `out_len` must be writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rl_precompile(wasm: *const u8, len: usize, out: *mut *mut u8, out_len: *mut usize) -> i32 {
    let input = if len == 0 {
        &[][..]
    } else {
        unsafe { slice::from_raw_parts(wasm, len) }
    };
    let (code, bytes) = match catch_unwind(AssertUnwindSafe(|| precompile(input))) {
        Ok(Ok(module)) => (OK, module),
        Ok(Err(e)) => (COMPILE_ERROR, format!("{e:?}").into_bytes()),
        Err(panic) => {
            let msg = panic
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".to_string());
            (
                PANIC,
                format!("wasmtime panicked while precompiling: {msg}").into_bytes(),
            )
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
/// `p` / `len` must be a pair `rl_precompile` returned, released once.
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

    #[test]
    fn version_is_the_shared_fingerprint() {
        let v = unsafe { CStr::from_ptr(rl_version()) };
        assert_eq!(v.to_str().unwrap(), rlabi::FINGERPRINT);
    }

    #[test]
    fn invalid_wasm_comes_back_as_an_error_message() {
        let bad = b"not wasm";
        let (mut out, mut out_len) = (std::ptr::null_mut(), 0usize);
        let code = unsafe { rl_precompile(bad.as_ptr(), bad.len(), &mut out, &mut out_len) };
        assert_eq!(code, COMPILE_ERROR);
        let msg = String::from_utf8(unsafe { slice::from_raw_parts(out, out_len) }.to_vec()).unwrap();
        assert!(!msg.is_empty());
        unsafe { rl_free(out, out_len) };
    }

    #[test]
    fn empty_input_is_an_error_not_a_crash() {
        let (mut out, mut out_len) = (std::ptr::null_mut(), 0usize);
        let code = unsafe { rl_precompile(std::ptr::null(), 0, &mut out, &mut out_len) };
        assert_eq!(code, COMPILE_ERROR);
        unsafe { rl_free(out, out_len) };
    }
}
