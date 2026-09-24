//! The shim and the stub together: every fixture `.wasm` (rontolisp output, see
//! `gen-fixtures.sh`) is precompiled here, appended to the BUILT runner stub, and run; its
//! stdout and exit status must be what `wasmtime run` gave for the same module.
//!
//! The stub is the release binary `build.sh` produces (runtime-only features), not one
//! built for this test: a test build would unify the compiler into it. Override its path
//! with `RLNATIVE_STUB`.

use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Output, Stdio};

fn stub() -> PathBuf {
    let path = std::env::var_os("RLNATIVE_STUB").map(PathBuf::from).unwrap_or_else(|| {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../target")
            .join(stub_in_target())
    });
    assert!(
        path.is_file(),
        "no runner stub at {}: run ./build.sh first",
        path.display()
    );
    path
}

/// Where build.sh leaves the stub under `target/`: Linux builds it for an explicit
/// `<arch>-unknown-linux-gnu` target (the static-glibc flag must stay off build scripts).
fn stub_in_target() -> PathBuf {
    if cfg!(target_os = "linux") {
        Path::new(&format!("{}-unknown-linux-gnu", std::env::consts::ARCH)).join("release-runner/rlrun")
    } else {
        PathBuf::from("release-runner/rlrun")
    }
}

fn fixtures() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures")
}

/// Writes `stub ++ module ++ trailer` into a fresh directory and runs it the way
/// gen-fixtures.sh runs `wasmtime run`: from `<dir>/cwd`, beside `<dir>/up.txt`.
fn run_executable(dir: &Path, module: &[u8]) -> Output {
    let exe = dir.join("prog");
    std::fs::write(&exe, rlabi::payload::assemble(&std::fs::read(stub()).unwrap(), module)).unwrap();
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
    child.stdin.take().unwrap().write_all(b"from stdin\n").unwrap();
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
    assert!(
        String::from_utf8_lossy(&out.stderr).contains("no module appended"),
        "{out:?}"
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
