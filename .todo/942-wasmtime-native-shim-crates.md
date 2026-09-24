# Wasmtime native shim: the precompile cdylib and the runner stub, as a built module

Difficulty: Medium

First of the `--native` chain (942 -> 943 -> 944 / 945 / 946): `--native -o prog` will
emit ONE executable = a runner stub + the wasm-GC backend's module precompiled by
Cranelift (through wasmtime) + a trailing u64 length. Measured feasibility, numbers and
every trap: `.todo/artefacts/942-wasmtime-native-shim/README.md` (spike sources included).

This item turns the two spike crates into a maintained module; no Java changes.

## What is needed

- A Cargo workspace OUTSIDE the root reactor (the core stays dependency-free), e.g.
  `rontolisp-native/` beside `docs-tool/`, holding:
  - `precomp` (cdylib): `rl_precompile(wasm, len, &out, &out_len) -> i32`, `rl_free`.
    Report the wasmtime version and a config fingerprint too (`rl_version`), so the Java
    side can refuse a mismatched stub instead of producing a binary that fails at start.
  - `runner` (bin, runtime-only features: `runtime std gc gc-copying` + `wasmtime-wasi`
    `p1`, no Cranelift): payload discovery, WASI P1 stdio/env/args, exit status via
    `I32Exit`, a trap message on stderr and a non-zero exit.
- ONE shared `Config` builder used by both (GC, function-references, exceptions, tail-call,
  copying collector) -- a module precompiled under any other config is refused at load.
- Pin wasmtime exactly (`=47.x`) with a committed `Cargo.lock`; rustc >= 1.96 is the floor.
- Copying collector, never DRC (9x slower on the GC corpus); wasmtime >= 47 (45's copying
  collector cannot satisfy the 16 MiB heap pregrow).
- Build the dylib with `-Wl,-install_name,@rpath/librlprecomp.dylib`.
- A build script producing `precomp` + `runner` for the host into a resource layout the
  Java side will load (`am/ik/rontolisp/native/<os>-<arch>/...`), and a Rust-side test that
  precompiles a `.wasm` from the ci corpus and runs it through the stub.

## Done when

`fib.lisp` / `gc.lisp` from the artefacts, compiled to `.wasm` by rontolisp, precompiled
by the built dylib and appended to the built stub, print what `wasmtime run` prints.
