# `--component` (WASI 0.3 / Preview 3 output) for the WASM compiler

Opt-in (CLI `--component`; `WasmLispCompiler(dynamic, component)`; threaded as a `component` boolean). Default output stays a Preview 1 core module (no regression). Run a component with `wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y` (wasmtime 46+).

**Design**: the rontolisp core module is emitted unchanged from Preview 1 (still imports the eight `wasi_snapshot_preview1` functions, all `FUNC_*` indices stable), and an **adapter** core module implements them over WASI 0.3's `stream<u8>`/`future<T>` + async canonical ABI. The component's `run` export is a **stackful** async `canon lift` (no callback), so the synchronous `stream.*`/`future.*` built-ins block cooperatively and the adapter stays straight-line.

**Gotchas to preserve**: `wasi:cli` and `wasi:filesystem` expose DISTINCT `error-code` types -> separate future built-ins (`future-read-cli`/`-fs`); the fs error-code is a string-bearing variant -> `future-read-fs` needs realloc.

**Index stability**: WASM static function-import indices and the `FUNC_*` constants in `WasmLispCompiler` are kept identical across modes (preview1-style `random_get`/`clock_time_get`/`environ_*` imports exist in both modes; `WasmRandomCompiler` calls `random_get` in both modes (real host entropy in Preview 1, the adapter's `wasi:random` in component); `WasmTimeCompiler` branches on `Ctx.component`; `FUNC_FETCH` is reserved in both modes).

Assembly lives in `WasmComponentBuilder` (codegen.wasm) over `am.ik.wasm.ComponentWriter` (general async-canon-ABI encoder, reusable for future language-level async). The fixed byte blobs (`import-block.bin`, `mem.wasm`, `adapter.wasm`) are loaded from classpath resources under `.../codegen/wasm/component/` and registered for native image in `resource-config.json` (wildcard). **The blobs are generated** from sources under `src/wasm-component/` — to change them follow `src/wasm-component/README.md` (edit sources, run `regen.sh`, re-derive the wiring constants from `wasm-tools dump`, re-test).

Encoders pinned by `ComponentWriterTest`; E2E by `WasmLispCompilerIntegrationTest`. Limitations (README "Compile to a WASI 0.3 component").
