use wasmtime::*;
use wasmtime_wasi::{WasiCtxBuilder, p1::{self, WasiP1Ctx}};

fn config() -> Config {
    let mut c = Config::new();
    c.wasm_gc(true).wasm_function_references(true).wasm_exceptions(true).wasm_tail_call(true)
        .collector(if std::env::var("RL_DRC").is_ok() { Collector::DeferredReferenceCounting } else { Collector::Copying });
    if let Ok(r) = std::env::var("RL_RES") { c.gc_heap_reservation(r.parse().unwrap()); }
    c
}

fn main() -> wasmtime::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    #[cfg(feature = "compiler")]
    if args.len() == 4 && args[1] == "--compile" {
        let engine = Engine::new(&config())?;
        std::fs::write(&args[3], engine.precompile_module(&std::fs::read(&args[2])?)?)?;
        return Ok(());
    }
    // Payload = the precompiled module appended after the runner, then its u64 length.
    let exe = std::fs::read(std::env::current_exe()?)?;
    let n = u64::from_le_bytes(exe[exe.len() - 8..].try_into()?) as usize;
    let image = &exe[exe.len() - 8 - n..exe.len() - 8];
    let engine = Engine::new(&config())?;
    let module = unsafe { Module::deserialize(&engine, image)? };
    let mut linker: Linker<WasiP1Ctx> = Linker::new(&engine);
    p1::add_to_linker_sync(&mut linker, |t| t)?;
    let wasi = WasiCtxBuilder::new().inherit_stdio().inherit_env().args(&args).build_p1();
    let mut store = Store::new(&engine, wasi);
    let inst = linker.instantiate(&mut store, &module)?;
    let start = inst.get_typed_func::<(), ()>(&mut store, "_start")?;
    match start.call(&mut store, ()) {
        Ok(()) => Ok(()),
        Err(e) => match e.downcast_ref::<wasmtime_wasi::I32Exit>() {
            Some(x) => std::process::exit(x.0),
            None => Err(e),
        },
    }
}
