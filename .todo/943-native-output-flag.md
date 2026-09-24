# `--native -o prog`: one self-contained executable from the wasm-GC backend

Difficulty: Medium

Depends on .todo/942 (the shim and the stub). Spike, numbers and traps:
`.todo/artefacts/942-wasmtime-native-shim/` (`native-spike/NativeSpike.java` is the whole
flow in ~60 lines; it ran on both `java -jar` and a native-image build).

## Flow

`.lisp` -> `CompileFrontend.run` (wasm options) -> `WasmLispCompiler` -> bytes ->
FFM `rl_precompile` -> bytes -> write `stub + cwasm + u64 le length + "RLNATIVE"` (the
trailer `rlabi::payload` defines; `rlpack` in `rontolisp-native/` is the same flow without
the JVM), chmod +x. Nothing else is written: `.wasm` and `.cwasm` stay in memory.

The shim and the stub exist (`rontolisp-native/`, `.kb/native-output.md`):
`rontolisp-native/build.sh` lays them out as
`am/ik/rontolisp/native/<linux|macos>-<x86_64|aarch64>/{librlprecomp.so|.dylib, rlrun}`
(map Java's `os.arch` `amd64` -> `x86_64`). Wiring that output into the jar / `-Pnative`
resources is part of this item (or 945's packaging decision).

## What is needed

- CLI: `--native` (with `-o`), refusing what the WASI P1 command module cannot be:
  `--component`, `--no-wasi`, `--no-gc`, `--host-*`, `--reentrant`, `--emit-js-glue`.
  The backend selection and the refusals sit beside the other `-o` checks in
  `RontoLispCli`; the pass pipeline is `CompileFrontend`'s, never restated.
- A loader in `eval`-or-`cli` (not `runtime`, not `am.ik.*`): read the dylib and the stub
  from resources for the host `<os>-<arch>`, extract the dylib once to a content-addressed
  cache path (`$XDG_CACHE_HOME` / `~/Library/Caches` / override property), write via temp
  file + atomic move, `SymbolLookup.libraryLookup`. Absent resources for the host -> a
  clear "--native is not available for <os>-<arch>" error, not a link error. Check the
  shim's `rl_version()` against the stub's fingerprint: scan the stub bytes for
  `RLNATIVE-FINGERPRINT=` and read up to the NUL (`rlabi::STUB_MARKER`).
- native-image: register both downcall shapes in `reachability-metadata.json`
  (`(void*, jlong, void*, void*) -> jint`, `(void*, jlong) -> void`) and the resources.
  FFM inside the image is interpreted (~2 us a call, `.kb/native-downcalls.md`); two calls
  per compile need no substitution.
- `-Pweb` has no filesystem and no FFM: the entry point must not be reachable there.
- Tests: a unit test for the payload layout; an E2E (gated like the wasmtime ones) that
  compiles and runs a ci-spec slice with `--native` and diffs against `wasmtime run`.
- Docs: `doc/en` + `doc/ja` CLI page (relative paths cannot leave the current directory;
  the output's size is ~11x the `.wasm`); extend `.kb/native-output.md` with the Java
  side (the loader, the cache, the refusals).
- The stub preopens `.` and `/`, where `CiSpecE2eTest` runs `wasmtime` with `--dir . --dir
  /tmp`: the whole ci corpus printed identical stdout under both (2026-09-24, Linux).

## Measured targets (2026-09-24, arm64 macOS, native-image)

frontend 170-200 ms, precompile 6-180 ms, output 1.8-2.0 MB, hello starts < 10 ms,
`gc.lisp` 1.32 s (= `wasmtime run`). Linux x86_64 (2026-09-24, `.kb/native-output.md`): the
whole ci corpus (7.6 MB `.wasm`) precompiles in 11.7 s on 64 cores into an 84 MB output.
