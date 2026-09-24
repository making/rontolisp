# Native-executable output: the wasmtime shim and the runner stub

**Invariant**: a native output is `stub ++ module ++ u64-le(module length) ++ "RLNATIVE"`,
where `module` is the wasm-GC backend's Preview 1 module precompiled by the shim and `stub`
is the runner. Shim and stub build their engine from ONE function (`rlabi::config`) of ONE
exactly pinned wasmtime; a module precompiled under any other engine is refused by wasmtime
at start-up, so the Java side must compare fingerprints BEFORE writing an output.

Code: `rontolisp-native/` -- a Cargo workspace outside the Maven reactor (the core stays
dependency-free). `abi/` (`rlabi`: config, `FINGERPRINT`, `STUB_MARKER`, `payload`),
`precomp/` (`rlprecomp` cdylib + `rlpack` CLI), `runner/` (`rlrun`). Build and test:
`rontolisp-native/build.sh [--test]`; fixtures: `rontolisp-native/gen-fixtures.sh`.

## The two halves

- **Shim** `librlprecomp.{so,dylib}`: `rl_precompile(wasm, len, &out, &out_len) -> i32`
  (0 = module, 1 = compile error, 2 = caught panic; `*out` holds the module or the UTF-8
  message either way), `rl_free(out, out_len)`, `rl_version() -> const char*` (static
  `FINGERPRINT`). Features `cranelift` + `parallel-compilation`. The release profile keeps
  `panic = unwind` and `rl_precompile` catches at the boundary: it runs inside a JVM.
  `install_name @rpath/librlprecomp.dylib` / `soname librlprecomp.so` via `precomp/build.rs`.
- **Stub** `rlrun`: runtime-only wasmtime (`runtime std gc gc-copying`, no Cranelift) +
  `wasmtime-wasi` `p1`; profile `release-runner` (`panic = abort`). Reads its trailer from
  `/proc/self/exe` (Linux) or `current_exe()`, runs `_start` with inherited stdio, argv and
  environment; preopens the current directory as fd 3 (what a relative path resolves against,
  `.kb/read-load-streams.md`) under its ABSOLUTE name (`current_dir()`, `.` when unknown or
  not UTF-8) and `/` as fd 4 (covers every absolute path). The absolute name is what lets
  `../x` leave the current directory: the module joins a climbing relative path onto it and
  resolves it through `/`. Exit: the
  `proc_exit` code, 0 on return, **134 after a trap** (what `wasmtime run` answers on Unix,
  `Error: <trap>` on stderr), 1 when the module cannot load (no trailer, refused engine).
- **Fingerprint** `rlnative-abi=1;wasmtime=49.0.0;wasm=gc,function-references,exceptions,tail-call;collector=copying`.
  The stub carries `RLNATIVE-FINGERPRINT=<fingerprint>\0` in its read-only data (kept by a
  `black_box` in `main`); the assembler scans the stub for it and compares with
  `rl_version()`. Bump `rlnative-abi` when the trailer or the C ABI changes; edit the
  fingerprint together with `rlabi::config`.

## The Java side (`--native -o prog`)

`RontoLispCli.compileRecorded`: `refuseNativeConflicts` first (`--component`, `--no-wasi`,
`--no-gc`, `--host-random`, `--host-fetch`, `--host-boundary`, `--reentrant`,
`--emit-js-glue`, and an `-o` ending in `.wasm`/`.class`/`.jar`/`.war`), then
`NativeToolchain.load()` so a host without a shim fails before the front end runs. The
module is the ordinary wasm-GC Preview 1 path (`wasmOutput` = `--native` or `.wasm`);
only the write differs: `NativeExecutable.assemble(stub, precompile(wasm))`, written to a
temp file beside `-o`, chmod 0755, atomically moved over it (writing in place fails with
ETXTBSY while the previous output still runs). No `.wasm` or `.cwasm` touches disk.

- **Resources**: `am/ik/rontolisp/native/<linux|macos>-<x86_64|aarch64>/{librlprecomp.so|.dylib, rlrun}`
  on the classpath. `pom.xml` adds `rontolisp-native/target/resources` as a resource
  directory, so a jar built after `rontolisp-native/build.sh` carries the host's pair (+4.2 MB
  compressed on Linux x86_64: 12.3 MB exec jar); without it the build is unchanged and
  `--native` answers "not available for <os>-<arch>". Shipping every platform: `.todo/945`.
- **Cache**: `dlopen` needs a file. The shim is extracted to
  `<cache>/native/<sha256 16 hex>/<lib>` via temp file + atomic move; a file of the right
  size there is reused. `<cache>` = `-Drontolisp.native.cache`, else an absolute
  `$XDG_CACHE_HOME/rontolisp`, else `~/Library/Caches/rontolisp` (macOS) /
  `~/.cache/rontolisp`.
- **Fingerprint**: `rl_version()` must be AMONG the NUL-terminated strings following
  `RLNATIVE-FINGERPRINT=` in the stub (`NativeExecutable.stubFingerprints`), else
  `IllegalStateException` before anything is written.
- **native-image**: `reachability-metadata.json` registers `rl_precompile` and `rl_free`
  (the first two downcalls; `rl_version`'s `() -> void*` is Metal's shape), pinned by
  `NativeToolchainTest`; `resource-config.json` includes `am/ik/rontolisp/native/.*` when
  `NativeToolchain` is reachable. `-Pweb` never reaches `cli`.
- **Tests**: `NativeExecutableTest` (layout, marker scan, write), `NativeToolchainTest`,
  `RontoLispCliTest.nativeRefuses...`, and `NativeOutputE2eTest` (a ci-spec slice + argv,
  and a trap's exit status, diffed against `wasmtime run --dir . --dir /tmp`; and a `../`
  read from a subdirectory, which wasmtime refuses), which skips
  without wasmtime on `PATH` or without the host's resources.

## Traps

- **Build the two in separate cargo invocations.** Cargo unifies a dependency's features
  across the packages of one build, so `cargo build --workspace` puts Cranelift into the
  stub. `build.sh` builds `-p rlprecomp` and `-p rlrun` apart; the stub test uses the
  `release-runner` binary, never a test build (`cargo test` unifies too).
- **Collector**: copying, never DRC (DRC ran `gc.lisp` 9x slower, 12.1 s vs 1.4 s, arm64
  macOS). Homebrew's C API defaults to DRC with no setter -- hence Rust, not the C API.
- **wasmtime >= 47**: 45's copying collector fails the 16 MiB heap pregrow
  (`.kb/wasm-gc-heap-pregrow.md`) on every program. The pin is 49.0.0 (2026-09-24), rustc >= 1.96
  (49's MSRV). 48 replaced `wasmtime-wasi`'s `DirPerms`/`FilePerms` pair with one `FsPerms`
  (read-only or read-write per preopen); the stub asks `ReadWrite` for both.
- **Engine settings are part of the artifact**: a module precompiled with a different GC
  heap reservation is refused ("heap reservation"); pinned by
  `module_precompiled_under_another_config_is_refused_at_start` in
  `rontolisp-native/precomp/tests/stub.rs`.
- **`..` and the sandbox**: every preopen is a cap-std sandbox that refuses a `..` leaving
  it, so a relative `../x` needs fd 3's absolute name above; under `wasmtime run --dir .` it
  still answers the ordinary open errno. `/..` (climbing above the root) is refused, where a
  native program would stay at `/`.
- **macOS signature**: the appended payload is outside the linker's ad-hoc signature
  (`codesign -v` fails strict validation; it still runs from a shell) -- `.todo/944`.
- **ETXTBSY in a multi-threaded test**: exec of a just-written output fails while another
  thread's fork still holds the write descriptor (until that child's exec). `stub.rs`
  retries the spawn; a Java E2E that writes and runs outputs in parallel needs the same.
- **CPU features**: the shim targets the HOST CPU; an output may not run on an older one.

## Numbers

2026-09-24, Linux x86_64 (64 cores), rustc 1.98.1, wasmtime 47.0.3:

| | shim | stub |
|---|---|---|
| size (stripped, LTO) | 10.0 MB | 2.0 MB (glibc-dynamic) |

| program | `.wasm` | precompile | output | run | `wasmtime run` |
|---|---|---|---|---|---|
| `fib.lisp` | 2,865 B | 0.05 s | 2.0 MB | 0.30 s | 0.28 s |
| `gc.lisp` | 28,403 B | 0.05 s | 2.2 MB | 3.60 s | 3.54 s |
| `(print "hello")` | | | | 0.02 s, 20 MB RSS | |
| whole ci-spec corpus (581 cases) | 7.6 MB | 11.7 s (90.6 s serial) | 84 MB | 17.6 s | 27.6 s |

Re-measured on **49.0.0** (same day, same host, `rlpack` + stub, three runs): shim 10.2 MB,
stub 2.0 MB; `fib` 0.30-0.31 s (`wasmtime run` 0.27-0.37 s), output 2,042,032 B; `gc`
3.57-3.59 s (`wasmtime run` 3.29-3.48 s), output 2,236,008 B -- the same picture as 47.0.3.

Through the CLI (2026-09-24, same host, load average ~12, `java -jar` exec jar):
`--native -o` costs 0.3 s over `-o x.wasm` (2.96 s vs 2.69 s for `gc.lisp`, JVM start
included); outputs `hello` 2,006,592 B, `fib` 2,025,472 B, `gc` 2,227,640 B; `gc` ran
3.65-3.72 s against `wasmtime run`'s 3.34-3.39 s (+9%, where the stub-only run above was
+2%), `fib` 0.37 s, hello 0.01 s / 20 MB RSS. The Java-assembled `gc` is byte-identical to
`rlpack`'s for the same `.wasm`, so any gap is the stub's, not the assembler's.
`-Pnative` binary (102,500,616 B, carrying the 12.0 MB pair as resources): `--native -o`
takes 0.59 s for hello and 0.72 s for `gc.lisp` (0.80 s on the first call, which extracts
the shim); it also compiles under `env -i` (cache from `user.home`).

The ci corpus printed byte-identical stdout (4,722 lines) under the stub (`.` + `/`, before
fd 3 carried the absolute name) and `wasmtime run --dir . --dir /tmp`. `parallel-compilation` is deterministic: serial and
parallel outputs were byte-identical. A precompiled module is ~11x its `.wasm`.
arm64 macOS spike numbers (2026-09-24): shim 5.5 MB, stub 1.7 MB, `gc.lisp` 1.32 s (=
`wasmtime run`), hello starts < 10 ms.

Pinned by `rontolisp-native/precomp/tests/stub.rs` (fixtures compiled by rontolisp, run from
`<tmp>/cwd` beside `<tmp>/up.txt`; stdout and exit status equal to `wasmtime run`'s with the
stub's preopens, recorded by `gen-fixtures.sh`) and the
`rlabi` / `rlprecomp` unit tests.
