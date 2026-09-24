//! `rlpack STUB IN.wasm OUT`: precompiles IN.wasm and writes STUB + module + trailer to OUT
//! (mode 0755) -- what `--native -o OUT` does, without the JVM. For scripts and tests.

use std::process::exit;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 4 {
        eprintln!("usage: rlpack STUB IN.wasm OUT");
        exit(2);
    }
    if let Err(e) = pack(&args[1], &args[2], &args[3]) {
        eprintln!("rlpack: {e:?}");
        exit(1);
    }
}

fn pack(stub: &str, wasm: &str, out: &str) -> wasmtime::Result<()> {
    let stub = std::fs::read(stub)?;
    let marker = rlabi::STUB_MARKER.as_bytes();
    if !stub.windows(marker.len()).any(|w| w == marker) {
        wasmtime::bail!(
            "the stub does not carry this shim's fingerprint ({})",
            rlabi::FINGERPRINT
        );
    }
    let module = rlprecomp::precompile(&std::fs::read(wasm)?)?;
    std::fs::write(out, rlabi::payload::assemble(&stub, &module))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(out, std::fs::Permissions::from_mode(0o755))?;
    }
    Ok(())
}
