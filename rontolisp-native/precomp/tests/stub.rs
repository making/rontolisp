//! The shim and the stub together: every fixture `.wasm` (rontolisp output, see
//! `gen-fixtures.sh`) is precompiled here, assembled with the BUILT runner stub, and run; its
//! stdout and exit status must be what `wasmtime run` gave for the same module -- but
//! `fetch`'s, which only the network runner can run: what this test's HTTPS origin answers.
//!
//! The stub is the release binary `build.sh` produces (runtime-only features), not one
//! built for this test: a test build would unify the compiler into it. Override its path
//! with `RLNATIVE_STUB`, and the network runner's (`rlrun-net`) with `RLNATIVE_NET_STUB`.

use std::ffi::OsStr;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Output, Stdio};
use std::sync::Arc;

fn stub() -> PathBuf {
    built_stub("RLNATIVE_STUB", "rlrun")
}

/// The network runner, which `build.sh` leaves beside the plain one.
fn net_stub() -> PathBuf {
    built_stub("RLNATIVE_NET_STUB", "rlrun-net")
}

fn built_stub(var: &str, name: &str) -> PathBuf {
    let path = std::env::var_os(var).map(PathBuf::from).unwrap_or_else(|| {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../target")
            .join(stub_in_target())
            .with_file_name(name)
    });
    assert!(
        path.is_file(),
        "no runner stub at {}: run ./build.sh first",
        path.display()
    );
    path
}

/// Where build.sh leaves the stub under `target/`: Linux builds it for an explicit
/// `<arch>-unknown-linux-musl` target, linked statically.
fn stub_in_target() -> PathBuf {
    if cfg!(target_os = "linux") {
        Path::new(&format!("{}-unknown-linux-musl", std::env::consts::ARCH)).join("release-runner/rlrun")
    } else {
        PathBuf::from("release-runner/rlrun")
    }
}

fn fixtures() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures")
}

/// Writes the executable (`rlprecomp::assemble`) into a fresh directory and runs it the way
/// gen-fixtures.sh runs `wasmtime run`: from `<dir>/cwd`, beside `<dir>/up.txt`.
fn run_executable(dir: &Path, module: &[u8]) -> Output {
    run_executable_on(&stub(), dir, module)
}

/// [`run_executable`] under the runner `stub`.
fn run_executable_on(stub: &Path, dir: &Path, module: &[u8]) -> Output {
    let exe = dir.join("prog");
    // A fresh file, never one rewritten in place: macOS caches a signed executable's
    // code signature by vnode, and running new bytes under a cached signature is a
    // SIGKILL -- which is what a second run in one directory got.
    let _ = std::fs::remove_file(&exe);
    std::fs::write(
        &exe,
        rlprecomp::assemble(&std::fs::read(stub).unwrap(), module).unwrap(),
    )
    .unwrap();
    make_executable(&exe);
    let cwd = dir.join("cwd");
    std::fs::create_dir_all(&cwd).unwrap();
    std::fs::write(dir.join("up.txt"), "up\n").unwrap();
    let mut command = Command::new(&exe);
    command
        .args(["alpha", "beta gamma"])
        .current_dir(&cwd)
        .env("RLNATIVE_TEST", "hello")
        .env("RLNATIVE_DIR", &cwd)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = spawn_fresh_executable(&mut command);
    // The child may already have exited without reading stdin (a refused module
    // exits 1 at once), closing the pipe before this write: Rust ignores SIGPIPE,
    // so that lands here as BrokenPipe rather than a signal. Tolerate it -- the
    // exit-status and stderr assertions below are the real checks, and an
    // unexpectedly early exit still fails those.
    match child.stdin.take().unwrap().write_all(b"from stdin\n") {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::BrokenPipe => {}
        Err(e) => panic!("stdin write: {e}"),
    }
    child.wait_with_output().unwrap()
}

/// Spawns an executable this process has just written. Another test thread forking while
/// the file was open for writing leaves a copy of that descriptor in its child until the
/// child's exec, and exec of a file open for writing fails with ETXTBSY: retry briefly.
fn spawn_fresh_executable(command: &mut Command) -> Child {
    const ETXTBSY: i32 = 26;
    let mut attempts = 0;
    loop {
        match command.spawn() {
            Err(e) if e.raw_os_error() == Some(ETXTBSY) && attempts < 50 => {
                attempts += 1;
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
            result => return result.unwrap(),
        }
    }
}

#[cfg(unix)]
fn make_executable(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755)).unwrap();
}

struct TempDir(PathBuf);

impl TempDir {
    fn new(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("rlnative-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        TempDir(dir.canonicalize().unwrap())
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn check_fixture(name: &str) {
    let wasm = std::fs::read(fixtures().join(format!("{name}.wasm"))).unwrap();
    let expected_out = std::fs::read_to_string(fixtures().join(format!("{name}.out"))).unwrap();
    let expected_status: i32 = std::fs::read_to_string(fixtures().join(format!("{name}.status")))
        .unwrap()
        .trim()
        .parse()
        .unwrap();
    let module = rlprecomp::precompile(&wasm).unwrap();
    let dir = TempDir::new(name);
    let out = run_executable(&dir.0, &module);
    let stderr = String::from_utf8_lossy(&out.stderr);
    assert_eq!(
        String::from_utf8_lossy(&out.stdout),
        expected_out,
        "{name} stdout; stderr:\n{stderr}"
    );
    assert_eq!(
        out.status.code(),
        Some(expected_status),
        "{name} exit status; stderr:\n{stderr}"
    );
}

#[test]
fn ci_spec_slice() {
    check_fixture("ci-slice");
}

#[test]
fn fib_and_tak() {
    check_fixture("fib");
}

#[test]
fn gc_pressure_conditions_hash_and_bignum() {
    check_fixture("gc");
}

#[test]
fn arguments_environment_stdin_files_and_exit_status() {
    check_fixture("host");
}

#[test]
fn relative_paths_climb_above_the_current_directory() {
    check_fixture("updir");
}

#[test]
fn trap_exits_134_with_the_trap_on_stderr() {
    check_fixture("trap");
    let module = rlprecomp::precompile(&std::fs::read(fixtures().join("trap.wasm")).unwrap()).unwrap();
    let dir = TempDir::new("trap-stderr");
    let out = run_executable(&dir.0, &module);
    assert!(String::from_utf8_lossy(&out.stderr).contains("wasm trap"), "{out:?}");
}

#[test]
fn stub_carries_the_shim_fingerprint() {
    let bytes = std::fs::read(stub()).unwrap();
    let marker = rlabi::STUB_MARKER.as_bytes();
    assert!(
        bytes.windows(marker.len()).any(|w| w == marker),
        "marker {} absent",
        rlabi::STUB_MARKER
    );
}

#[test]
fn bare_stub_reports_the_missing_module() {
    let out = Command::new(stub()).output().unwrap();
    assert_eq!(out.status.code(), Some(1));
    assert!(String::from_utf8_lossy(&out.stderr).contains("no module"), "{out:?}");
}

/// `(module (import "rlhttp" "start" (func (param i32 i32) (result externref)))
/// (func (export "_start")))`: a module importing what a fetch imports
/// (`eval/HostFetchLibrary`'s runner shape), with the signature `http::add_to_linker` binds.
const FETCHING_MODULE: &[u8] = &[
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, // magic, version
    0x01, 0x0a, 0x02, 0x60, 0x02, 0x7f, 0x7f, 0x01, 0x6f, 0x60, 0x00, 0x00, // types
    0x02, 0x10, 0x01, 0x06, b'r', b'l', b'h', b't', b't', b'p', 0x05, b's', b't', b'a', b'r', b't', 0x00,
    0x00, // import rlhttp.start
    0x03, 0x02, 0x01, 0x01, // one function of type 1
    0x07, 0x0a, 0x01, 0x06, b'_', b's', b't', b'a', b'r', b't', 0x00, 0x01, // export _start
    0x0a, 0x04, 0x01, 0x02, 0x00, 0x0b, // its empty body
];

/// A module that fetches starts with the network runner, which links its `rlhttp`
/// imports; the plain runner refuses it naming the one that would run it.
#[test]
fn a_fetching_module_runs_on_the_network_runner_and_the_plain_one_names_it() {
    let module = rlprecomp::precompile(FETCHING_MODULE).unwrap();
    let dir = TempDir::new("fetching");
    let net = run_executable_on(&net_stub(), &dir.0, &module);
    assert_eq!(net.status.code(), Some(0), "{net:?}");
    let plain = run_executable_on(&stub(), &dir.0, &module);
    assert_eq!(plain.status.code(), Some(1), "{plain:?}");
    assert!(
        String::from_utf8_lossy(&plain.stderr).contains("whose host is the network runner (rlrun-net)"),
        "{plain:?}"
    );
}

#[test]
fn module_precompiled_under_another_config_is_refused_at_start() {
    let mut other = rlabi::config();
    other.gc_heap_reservation(8 << 30);
    let wasm = std::fs::read(fixtures().join("fib.wasm")).unwrap();
    let module = wasmtime::Engine::new(&other).unwrap().precompile_module(&wasm).unwrap();
    let dir = TempDir::new("mismatch");
    let out = run_executable(&dir.0, &module);
    assert_eq!(out.status.code(), Some(1), "{out:?}");
    assert!(
        String::from_utf8_lossy(&out.stderr).contains("heap reservation"),
        "{out:?}"
    );
}

/// The runner stub `runner` (`rlrun`, `rlrun-net`) of `platform`: the one this test runs
/// against for the host, else what `build.sh --stub` laid out under `$RLNATIVE_STUBS`
/// (default `target/resources`), if any.
fn stub_for(platform: &rlprecomp::Platform, runner: &str) -> Option<PathBuf> {
    if rlprecomp::host_platform() == Some(platform) {
        return Some(if runner == "rlrun" { stub() } else { net_stub() });
    }
    let root = std::env::var_os("RLNATIVE_STUBS")
        .map(PathBuf::from)
        .unwrap_or_else(|| Path::new(env!("CARGO_MANIFEST_DIR")).join("../target/resources"));
    let path = root.join("am/ik/rontolisp/native").join(platform.name).join(runner);
    path.is_file().then_some(path)
}

/// `qemu-<arch>` (user mode) when it is installed; a non-empty `RLNATIVE_REQUIRE_QEMU` (CI)
/// turns its absence into a failure instead of a skipped check.
fn qemu(arch: &str) -> Option<PathBuf> {
    let found = [format!("qemu-{arch}-static"), format!("qemu-{arch}")]
        .into_iter()
        .filter_map(|name| {
            std::env::split_paths(&std::env::var_os("PATH")?)
                .map(|dir| dir.join(&name))
                .find(|p| p.is_file())
        })
        .next();
    if found.is_none() {
        assert!(
            !std::env::var("RLNATIVE_REQUIRE_QEMU").is_ok_and(|v| !v.is_empty()),
            "RLNATIVE_REQUIRE_QEMU is set but qemu-{arch} is not on PATH"
        );
        eprintln!("qemu-{arch} not on PATH: skipped");
    }
    found
}

/// Whether `qemu` can actually run an executable of its architecture HERE. qemu-x86_64
/// 8.2.2 crashes with an internal SIGSEGV on an aarch64 host as soon as the guest opens
/// /proc/self/maps -- Rust's startup guard setup does (`std/.../stack_overflow.rs`), and
/// even a C program that only opens it dies the same way -- while qemu-aarch64 on either
/// host and qemu-x86_64 on an x86_64 host are fine. Probe with the bare stub, which must
/// exit 1 naming the missing module; anything else (a qemu crash lands here as a signal,
/// not an exit code) skips the emulated runs for that architecture.
/// `RLNATIVE_REQUIRE_QEMU` still requires qemu to be INSTALLED -- this only excuses a
/// qemu that cannot run.
fn qemu_runs_stub(qemu: &Path, stub: &Path) -> bool {
    let out = Command::new(qemu)
        .arg(stub)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .output();
    match out {
        Ok(out) if out.status.code() == Some(1) && String::from_utf8_lossy(&out.stderr).contains("no module") => true,
        _ => {
            eprintln!(
                "{} cannot run {} here: emulated runs for it are skipped",
                qemu.display(),
                stub.display()
            );
            false
        }
    }
}

/// The oldest CPU qemu emulates that is still a CPU of the platform: SSE2 and nothing
/// newer for x86_64, the psABI baseline (qemu's Opteron G1 also reports SSE3, a level-2
/// feature, which `-sse3` takes away), Armv8.0 (no LSE) for aarch64.
fn oldest_cpu(arch: &str) -> &'static str {
    match arch {
        "x86_64" => "Opteron_G1,-sse3",
        "aarch64" => "cortex-a53",
        other => panic!("no oldest CPU for {other}"),
    }
}

/// Runs `stub ++ module` under `qemu-<arch> -cpu <cpu>` from a fresh directory, with
/// `env` added to this process's environment.
fn run_under_qemu(qemu: &Path, cpu: &str, stub: &Path, module: &[u8], name: &str, env: &[(&str, &OsStr)]) -> Output {
    let dir = TempDir::new(name);
    let exe = dir.0.join("prog");
    std::fs::write(
        &exe,
        rlprecomp::assemble(&std::fs::read(stub).unwrap(), module).unwrap(),
    )
    .unwrap();
    make_executable(&exe);
    let mut command = Command::new(qemu);
    command
        .args(["-cpu", cpu])
        .arg(&exe)
        .envs(env.iter().copied())
        .current_dir(&dir.0)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    spawn_fresh_executable(&mut command).wait_with_output().unwrap()
}

/// The network runner's test TLS material (`runner/src/http/testdata`, never a runner's).
fn tls_testdata(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../runner/src/http/testdata")
        .join(name)
}

/// An HTTPS origin on 127.0.0.1 under the test root (rustls over ring, as the runner's
/// client): answers one GET with `secure`, then names the protocol version, cipher suite
/// and key exchange the handshake settled on.
fn tls_origin() -> (u16, std::thread::JoinHandle<Result<String, String>>) {
    use rustls::pki_types::pem::PemObject;
    use rustls::pki_types::{CertificateDer, PrivateKeyDer};

    let certs = CertificateDer::pem_file_iter(tls_testdata("localhost.pem"))
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    let key = PrivateKeyDer::from_pem_file(tls_testdata("localhost-key.pem")).unwrap();
    let config = Arc::new(
        rustls::ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(certs, key)
            .unwrap(),
    );
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let origin = std::thread::spawn(move || {
        let (tcp, _) = listener.accept().map_err(|e| e.to_string())?;
        let conn = rustls::ServerConnection::new(config).map_err(|e| e.to_string())?;
        let mut tls = rustls::StreamOwned::new(conn, tcp);
        let mut seen = Vec::new();
        let mut b = [0u8; 1024];
        while !seen.ends_with(b"\r\n\r\n") {
            match tls.read(&mut b) {
                Ok(0) => return Err("closed before the request ended".to_owned()),
                Ok(n) => seen.extend_from_slice(&b[..n]),
                Err(e) => return Err(e.to_string()),
            }
        }
        tls.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nsecure")
            .map_err(|e| e.to_string())?;
        tls.conn.send_close_notify();
        tls.flush().map_err(|e| e.to_string())?;
        Ok(format!(
            "{:?} {:?} {:?}",
            tls.conn.protocol_version(),
            tls.conn.negotiated_cipher_suite().map(|s| s.suite()),
            tls.conn.negotiated_key_exchange_group().map(|g| g.name())
        ))
    });
    (port, origin)
}

/// The `fetch` fixture precompiled for `platform` at its baseline, run under the network
/// runner `stub` on the oldest CPU of `arch`: an HTTPS GET from an origin in this process
/// that the output trusts through `SSL_CERT_FILE`. A handshake runs code nothing else
/// reaches (ring's C and assembly, rustls), so a stub built for a newer CPU than its
/// platform's passes the other emulated runs and faults here.
fn check_tls_fetch_under_qemu(qemu: &Path, arch: &str, stub: &Path, platform: &rlprecomp::Platform, name: &str) {
    let wasm = std::fs::read(fixtures().join("fetch.wasm")).unwrap();
    let expected = std::fs::read_to_string(fixtures().join("fetch.out")).unwrap();
    let module = rlprecomp::precompile_for(&wasm, platform, rlprecomp::Cpu::Baseline).unwrap();
    let (port, origin) = tls_origin();
    let url = format!("https://127.0.0.1:{port}/");
    let ca = tls_testdata("ca.pem");
    let started = std::time::Instant::now();
    let out = run_under_qemu(
        qemu,
        oldest_cpu(arch),
        stub,
        &module,
        name,
        &[("RLNATIVE_URL", url.as_ref()), ("SSL_CERT_FILE", ca.as_os_str())],
    );
    let stderr = String::from_utf8_lossy(&out.stderr);
    assert_eq!(
        String::from_utf8_lossy(&out.stdout),
        expected,
        "{name}; stderr:\n{stderr}"
    );
    assert_eq!(out.status.code(), Some(0), "{name}; stderr:\n{stderr}");
    let settled = origin.join().unwrap().unwrap();
    eprintln!("{name}: {settled} in {:?}", started.elapsed());
}

/// Linux only: the stub reads its module from `/proc/self/exe`, which qemu user mode
/// answers with the guest executable. The oldest CPU runs fib on the plain runner and an
/// HTTPS fetch on the network one.
#[test]
#[cfg(target_os = "linux")]
fn baseline_output_runs_on_the_oldest_cpu_of_its_platform_and_a_host_output_is_refused_there() {
    let host = rlprecomp::host_platform().unwrap();
    let arch = std::env::consts::ARCH;
    let Some(qemu) = qemu(arch) else { return };
    if !qemu_runs_stub(&qemu, &stub()) {
        return;
    }
    let wasm = std::fs::read(fixtures().join("fib.wasm")).unwrap();
    let expected = std::fs::read_to_string(fixtures().join("fib.out")).unwrap();

    let baseline = rlprecomp::precompile_for(&wasm, host, rlprecomp::Cpu::Baseline).unwrap();
    let out = run_under_qemu(&qemu, oldest_cpu(arch), &stub(), &baseline, "oldest-baseline", &[]);
    let stderr = String::from_utf8_lossy(&out.stderr);
    assert_eq!(String::from_utf8_lossy(&out.stdout), expected, "stderr:\n{stderr}");
    assert_eq!(out.status.code(), Some(0), "stderr:\n{stderr}");
    check_tls_fetch_under_qemu(&qemu, arch, &net_stub(), host, "oldest-fetch");

    // Refused, not faulting: wasmtime checks every CPU feature the module was compiled
    // with before running it. Only meaningful where the host has a feature the oldest CPU
    // lacks, which every CI runner does.
    #[cfg(target_arch = "x86_64")]
    let newer = std::arch::is_x86_feature_detected!("sse3");
    #[cfg(target_arch = "aarch64")]
    let newer = std::arch::is_aarch64_feature_detected!("lse");
    if newer {
        let native = rlprecomp::precompile_for(&wasm, host, rlprecomp::Cpu::Host).unwrap();
        let out = run_under_qemu(&qemu, oldest_cpu(arch), &stub(), &native, "oldest-host", &[]);
        assert_eq!(out.status.code(), Some(1), "{out:?}");
        assert!(
            String::from_utf8_lossy(&out.stderr).contains("but not available on the host"),
            "{out:?}"
        );
    }
}

/// The section `name` of the ELF image `elf` (64-bit, little-endian: what wasmtime writes
/// on every platform, macOS included).
fn elf_section<'a>(elf: &'a [u8], name: &str) -> Option<&'a [u8]> {
    let u16_at = |at: usize| u16::from_le_bytes(elf[at..at + 2].try_into().unwrap()) as usize;
    let u64_at = |at: usize| u64::from_le_bytes(elf[at..at + 8].try_into().unwrap()) as usize;
    assert_eq!(&elf[..4], b"\x7fELF");
    let (shoff, shentsize, shnum, shstrndx) = (u64_at(0x28), u16_at(0x3a), u16_at(0x3c), u16_at(0x3e));
    let header = |i: usize| shoff + i * shentsize;
    let strtab = u64_at(header(shstrndx) + 0x18);
    (0..shnum).find_map(|i| {
        let h = header(i);
        let name_at = strtab + u32::from_le_bytes(elf[h..h + 4].try_into().unwrap()) as usize;
        let end = name_at + elf[name_at..].iter().position(|b| *b == 0)?;
        (&elf[name_at..end] == name.as_bytes()).then(|| {
            let (offset, size) = (u64_at(h + 0x18), u64_at(h + 0x20));
            &elf[offset..offset + size]
        })
    })
}

/// A module precompiled for another platform is machine code of that platform's
/// architecture, and its engine header names that platform's triple -- what the target's
/// stub checks before running it. Where the other platform's stub is present and qemu can
/// run it (the other Linux architecture), the output also has to print what the host's
/// does, and so does the HTTPS fetch on that platform's network runner.
#[test]
fn cross_target_module_names_the_requested_triple_and_runs_there() {
    let wasm = std::fs::read(fixtures().join("fib.wasm")).unwrap();
    let expected = std::fs::read_to_string(fixtures().join("fib.out")).unwrap();
    for platform in rlprecomp::PLATFORMS {
        let module = rlprecomp::precompile_for(&wasm, platform, rlprecomp::Cpu::Baseline).unwrap();
        let machine = u16::from_le_bytes(module[18..20].try_into().unwrap());
        let arch = platform.name.split_once('-').unwrap().1;
        assert_eq!(
            machine,
            match arch {
                "x86_64" => 62,
                "aarch64" => 183,
                other => panic!("{other}"),
            },
            "{}: ELF e_machine",
            platform.name
        );
        let engine = elf_section(&module, ".wasmtime.engine").expect(".wasmtime.engine");
        assert!(
            engine
                .windows(platform.triple.len())
                .any(|w| w == platform.triple.as_bytes()),
            "{}: .wasmtime.engine does not name {}",
            platform.name,
            platform.triple
        );

        if rlprecomp::host_platform() == Some(platform)
            || !cfg!(target_os = "linux")
            || !platform.name.starts_with("linux-")
        {
            continue;
        }
        let Some(stub) = stub_for(platform, "rlrun") else {
            eprintln!("no {} stub (build.sh --stub {}): not run", platform.name, platform.name);
            continue;
        };
        let Some(qemu) = qemu(arch) else { continue };
        if !qemu_runs_stub(&qemu, &stub) {
            continue;
        }
        let out = run_under_qemu(
            &qemu,
            oldest_cpu(arch),
            &stub,
            &module,
            &format!("cross-{}", platform.name),
            &[],
        );
        let stderr = String::from_utf8_lossy(&out.stderr);
        assert_eq!(
            String::from_utf8_lossy(&out.stdout),
            expected,
            "{}; stderr:\n{stderr}",
            platform.name
        );
        assert_eq!(out.status.code(), Some(0), "{}; stderr:\n{stderr}", platform.name);
        match stub_for(platform, "rlrun-net") {
            Some(net) => {
                check_tls_fetch_under_qemu(&qemu, arch, &net, platform, &format!("cross-fetch-{}", platform.name))
            }
            None => eprintln!("no {} rlrun-net: its fetch not run", platform.name),
        }
    }
}

/// Checks a macOS output the way the kernel does, on any host: its `__RLPAYLOAD,__payload`
/// section holds `module`, and its embedded ad-hoc `CodeDirectory` hashes every 4 KiB page
/// up to the signature. Returns the file offset of the module.
fn check_macos_output(exe: &[u8], module: &[u8]) -> usize {
    let le32 = |at: usize| u32::from_le_bytes(exe[at..at + 4].try_into().unwrap()) as usize;
    let be32 = |b: &[u8], at: usize| u32::from_be_bytes(b[at..at + 4].try_into().unwrap()) as usize;
    assert_eq!(le32(0), 0xfeed_facf, "not a Mach-O image");
    let (mut at, mut sig, mut payload) = (32, None, None);
    for _ in 0..le32(16) {
        match le32(at) {
            0x19 if &exe[at + 8..at + 19] == b"__RLPAYLOAD" => {
                let sect = at + 72;
                let size = u64::from_le_bytes(exe[sect + 40..sect + 48].try_into().unwrap()) as usize;
                payload = Some((le32(sect + 48), size));
            }
            0x1d => sig = Some((le32(at + 8), le32(at + 12))),
            _ => {}
        }
        at += le32(at + 4);
    }
    let (offset, size) = payload.expect("payload section");
    let section = &exe[offset..offset + size];
    assert_eq!(rlabi::payload::embedded(section).unwrap(), module);

    let (sigoff, siglen) = sig.expect("LC_CODE_SIGNATURE");
    assert_eq!(sigoff + siglen, exe.len(), "the signature ends the file");
    let blob = &exe[sigoff..sigoff + siglen];
    assert_eq!(be32(blob, 0), 0xfade_0cc0);
    let cd = &blob[be32(blob, 16)..];
    assert_eq!(be32(cd, 0), 0xfade_0c02);
    assert_eq!(be32(cd, 12), 0x2_0002, "adhoc | linker-signed");
    let (hash_off, slots, limit) = (be32(cd, 16), be32(cd, 28), be32(cd, 32));
    assert_eq!(limit, sigoff, "codeLimit");
    assert_eq!((cd[36], cd[37], cd[39]), (32, 2, 12), "SHA-256 of 4 KiB pages");
    assert_eq!(slots, sigoff.div_ceil(4096));
    for (i, page) in exe[..sigoff].chunks(4096).enumerate() {
        use sha2::Digest;
        let at = hash_off + 32 * i;
        assert_eq!(&cd[at..at + 32], sha2::Sha256::digest(page).as_slice(), "page {i}");
    }
    offset + rlabi::payload::TRAILER_LEN
}

/// A macOS output carries its module inside the signed image. Checked structurally on any
/// host that has the macOS stub (a Linux host compiles `--native-target macos-aarch64`
/// too); on macOS `codesign --verify --strict` must accept it, and must refuse it once a
/// byte of the module changes -- the signature covers the module.
#[test]
fn macos_output_embeds_the_module_under_a_valid_ad_hoc_signature() {
    let platform = rlprecomp::platform("macos-aarch64").unwrap();
    let Some(stub) = stub_for(platform, "rlrun") else {
        eprintln!("no macos-aarch64 stub: not checked");
        return;
    };
    let wasm = std::fs::read(fixtures().join("fib.wasm")).unwrap();
    let module = rlprecomp::precompile_for(&wasm, platform, rlprecomp::Cpu::Baseline).unwrap();
    let exe = rlprecomp::assemble(&std::fs::read(&stub).unwrap(), &module).unwrap();
    let module_at = check_macos_output(&exe, &module);
    if !cfg!(target_os = "macos") {
        return;
    }
    let dir = TempDir::new("signed");
    let path = dir.0.join("prog");
    std::fs::write(&path, &exe).unwrap();
    let codesign = |path: &Path| {
        Command::new("codesign")
            .args(["--verify", "--strict", "--verbose=2"])
            .arg(path)
            .output()
            .unwrap()
    };
    let out = codesign(&path);
    assert!(out.status.success(), "{out:?}");
    let mut tampered = exe.clone();
    tampered[module_at + module.len() / 2] ^= 1;
    let bad = dir.0.join("tampered");
    std::fs::write(&bad, &tampered).unwrap();
    assert!(!codesign(&bad).status.success(), "a changed module still verifies");
}

#[test]
fn a_stub_that_is_no_known_image_is_appended_to() {
    assert_eq!(
        rlprecomp::assemble(b"STUB", b"m").unwrap(),
        rlabi::payload::append(b"STUB", b"m")
    );
    let err = rlprecomp::macho::embed(b"\xcf\xfa\xed\xfe", b"m").unwrap_err();
    assert!(err.contains("Mach-O"), "{err}");
}
