//! The runner stub every `--native` output starts with.
//!
//! It finds the precompiled module appended to its own executable (`rlabi::payload`),
//! loads it into an engine built from `rlabi::config` (runtime only: this binary has no
//! compiler), and runs `_start` under WASI Preview 1 with the process's stdio, arguments
//! and environment. The current directory is preopened as fd 3 (what a relative path
//! resolves against) under its absolute name, and the root as `/`, so the program sees the
//! file system a native program would, `..` above the current directory included.
//!
//! Exit status: the program's `proc_exit` code, 0 when `_start` returns, 134 after a trap
//! (what `wasmtime run` answers on Unix), 1 when the module cannot be loaded.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::process::exit;

use rlabi::payload;
use wasmtime::{Engine, Linker, Module, Store, Trap};
use wasmtime_wasi::p1::{self, WasiP1Ctx};
use wasmtime_wasi::{DirPerms, FilePerms, I32Exit, WasiCtxBuilder};

/// `128 + SIGABRT`, the status `wasmtime run` exits with after a trap on Unix.
const TRAP_EXIT: i32 = 134;

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
    let image = read_payload()?;
    let engine = Engine::new(&rlabi::config())?;
    // SAFETY: the bytes are what rl_precompile produced for this config; wasmtime checks the
    // header, version and engine settings before using them.
    let module = unsafe { Module::deserialize(&engine, &image)? };
    drop(image);

    let args: Vec<String> = std::env::args_os().map(|a| a.to_string_lossy().into_owned()).collect();
    let mut wasi = WasiCtxBuilder::new();
    wasi.inherit_stdio()
        .inherit_env()
        .args(&args)
        .allow_blocking_current_thread(true);
    wasi.preopened_dir(".", &cwd_name(), DirPerms::all(), FilePerms::all())
        .map_err(|e| e.context("cannot preopen the current directory"))?;
    wasi.preopened_dir("/", "/", DirPerms::all(), FilePerms::all())
        .map_err(|e| e.context("cannot preopen /"))?;

    let mut linker: Linker<WasiP1Ctx> = Linker::new(&engine);
    p1::add_to_linker_sync(&mut linker, |t| t)?;
    let mut store = Store::new(&engine, wasi.build_p1());
    let instance = linker.instantiate(&mut store, &module)?;
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
fn read_payload() -> wasmtime::Result<Vec<u8>> {
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
