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
- **Fingerprint** `rlnative-abi=1;wasmtime=47.0.3;wasm=gc,function-references,exceptions,tail-call;collector=copying`.
  The stub carries `RLNATIVE-FINGERPRINT=<fingerprint>\0` in its read-only data (kept by a
  `black_box` in `main`); the assembler scans the stub for it and compares with
  `rl_version()`. Bump `rlnative-abi` when the trailer or the C ABI changes; edit the
  fingerprint together with `rlabi::config`.

## Traps

- **Build the two in separate cargo invocations.** Cargo unifies a dependency's features
  across the packages of one build, so `cargo build --workspace` puts Cranelift into the
  stub. `build.sh` builds `-p rlprecomp` and `-p rlrun` apart; the stub test uses the
  `release-runner` binary, never a test build (`cargo test` unifies too).
- **Collector**: copying, never DRC (DRC ran `gc.lisp` 9x slower, 12.1 s vs 1.4 s, arm64
  macOS). Homebrew's C API defaults to DRC with no setter -- hence Rust, not the C API.
- **wasmtime >= 47**: 45's copying collector fails the 16 MiB heap pregrow
  (`.kb/wasm-gc-heap-pregrow.md`) on every program. rustc >= 1.96 (47's MSRV).
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

The ci corpus printed byte-identical stdout (4,722 lines) under the stub (`.` + `/`, before
fd 3 carried the absolute name) and `wasmtime run --dir . --dir /tmp`. `parallel-compilation` is deterministic: serial and
parallel outputs were byte-identical. A precompiled module is ~11x its `.wasm`.
arm64 macOS spike numbers (2026-09-24): shim 5.5 MB, stub 1.7 MB, `gc.lisp` 1.32 s (=
`wasmtime run`), hello starts < 10 ms.

Pinned by `rontolisp-native/precomp/tests/stub.rs` (fixtures compiled by rontolisp, run from
`<tmp>/cwd` beside `<tmp>/up.txt`; stdout and exit status equal to `wasmtime run`'s with the
stub's preopens, recorded by `gen-fixtures.sh`) and the
`rlabi` / `rlprecomp` unit tests.
