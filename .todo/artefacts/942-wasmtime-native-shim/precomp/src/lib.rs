//! C ABI: .wasm bytes in, precompiled (Cranelift) module bytes out. Config must match the runner's.
use std::slice;
use wasmtime::*;

fn precompile(wasm: &[u8]) -> Result<Vec<u8>> {
    let mut c = Config::new();
    c.wasm_gc(true).wasm_function_references(true).wasm_exceptions(true).wasm_tail_call(true)
        .collector(Collector::Copying);
    Engine::new(&c)?.precompile_module(wasm)
}

/// Returns 0 on success; *out holds the module (or the error text), freed by rl_free.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rl_precompile(wasm: *const u8, len: usize, out: *mut *mut u8, out_len: *mut usize) -> i32 {
    let (code, bytes) = match precompile(unsafe { slice::from_raw_parts(wasm, len) }) {
        Ok(b) => (0, b),
        Err(e) => (1, format!("{e:?}").into_bytes()),
    };
    let mut b = bytes.into_boxed_slice();
    unsafe { *out_len = b.len(); *out = b.as_mut_ptr(); }
    std::mem::forget(b);
    code
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn rl_free(p: *mut u8, len: usize) {
    drop(unsafe { Box::from_raw(slice::from_raw_parts_mut(p, len)) });
}
