//! `rlpack [--platform P] [--cpu C] STUB IN.wasm OUT`: precompiles IN.wasm and writes
//! STUB + module + trailer to OUT (mode 0755) -- what `--native -o OUT` does, without the
//! JVM. `P` defaults to the host's platform, `C` to `baseline` (`rlprecomp::Cpu`). STUB must
//! be the runner stub built for `P`. For scripts and tests.

use std::process::exit;

const USAGE: &str = "usage: rlpack [--platform P] [--cpu baseline|host|LEVEL] STUB IN.wasm OUT";

fn main() {
    let mut args = std::env::args().skip(1);
    let mut platform = None;
    let mut cpu = "baseline".to_string();
    let mut files = Vec::new();
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--platform" => platform = Some(args.next().unwrap_or_else(|| usage())),
            "--cpu" => cpu = args.next().unwrap_or_else(|| usage()),
            _ => files.push(arg),
        }
    }
    let [stub, wasm, out] = files.as_slice() else { usage() };
    if let Err(e) = pack(platform.as_deref(), &cpu, stub, wasm, out) {
        eprintln!("rlpack: {e:?}");
        exit(1);
    }
}

fn usage() -> ! {
    eprintln!("{USAGE}");
    exit(2);
}

fn pack(platform: Option<&str>, cpu: &str, stub: &str, wasm: &str, out: &str) -> wasmtime::Result<()> {
    let platform = match platform {
        Some(name) => rlprecomp::platform(name)?,
        None => rlprecomp::host_platform().ok_or_else(|| wasmtime::format_err!("pass --platform"))?,
    };
    let stub = std::fs::read(stub)?;
    let marker = rlabi::STUB_MARKER.as_bytes();
    if !stub.windows(marker.len()).any(|w| w == marker) {
        wasmtime::bail!(
            "the stub does not carry this shim's fingerprint ({})",
            rlabi::FINGERPRINT
        );
    }
    let module = rlprecomp::precompile_for(&std::fs::read(wasm)?, platform, rlprecomp::Cpu::parse(cpu))?;
    std::fs::write(out, rlabi::payload::assemble(&stub, &module))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(out, std::fs::Permissions::from_mode(0o755))?;
    }
    Ok(())
}
