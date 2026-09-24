# `--native -o prog`: one self-contained executable from the wasm-GC backend

Difficulty: Medium

Depends on .todo/942 (the shim and the stub). Spike, numbers and traps:
`.todo/artefacts/942-wasmtime-native-shim/` (`native-spike/NativeSpike.java` is the whole
flow in ~60 lines; it ran on both `java -jar` and a native-image build).

## Flow

`.lisp` -> `CompileFrontend.run` (wasm options) -> `WasmLispCompiler` -> bytes ->
FFM `rl_precompile` -> bytes -> write `stub + cwasm + u64 le length`, chmod +x. Nothing
else is written: `.wasm` and `.cwasm` stay in memory.

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
  shim's `rl_version` against the stub's.
- native-image: register both downcall shapes in `reachability-metadata.json`
  (`(void*, jlong, void*, void*) -> jint`, `(void*, jlong) -> void`) and the resources.
  FFM inside the image is interpreted (~2 us a call, `.kb/native-downcalls.md`); two calls
  per compile need no substitution.
- `-Pweb` has no filesystem and no FFM: the entry point must not be reachable there.
- Tests: a unit test for the payload layout; an E2E (gated like the wasmtime ones) that
  compiles and runs a ci-spec slice with `--native` and diffs against `wasmtime run`.
- Docs: `doc/en` + `doc/ja` CLI page; a `.kb/native-output.md` with the invariants
  (config fingerprint, collector, the version lock, the cache).

## Measured targets (2026-09-24, arm64 macOS, native-image)

frontend 170-200 ms, precompile 6-180 ms, output 1.8-2.0 MB, hello starts < 10 ms,
`gc.lisp` 1.32 s (= `wasmtime run`).
