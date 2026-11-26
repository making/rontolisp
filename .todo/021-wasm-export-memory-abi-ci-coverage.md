# `wasm-export`: automated CI coverage for the Preview 1 `:string`/`:s-expr` memory ABI

**Status:** open, narrowed (inventory 2026-07-17). Follow-up to the
`wasm-export` feature. Raised in the `claude-opus` session 2026-06-28.

## Gap

The `--component` half is now **fully covered**: the canonical ABI lifts strings
for real, so `WasmLispCompilerIntegrationTest` round-trips them under wasmtime in
CI -- `componentExportStringLiftsThroughTheCanonicalStringAbi`
(`count-a("banana") => 3`, `shout("hello") => "hello!!"`,
`greet("世界") => "Hello, 世界"`, plus the no-arg and `:void` paths),
`componentExportSExprLiftsAsString`, and
`noGcComponentStringExportsLiftThroughTheCanonicalAbi` for `--no-gc --component`.
The `wasmtime --invoke` limitation was sidestepped by the canonical ABI + WAVE
invoke, not by a JS host.

What is still uncovered is the **Preview 1 raw `(ptr,len)` memory ABI**:

- `wasmtime --invoke` cannot write a pointer/length into linear memory, so CI can
  only confirm the module **instantiates and `_start` runs**
  (`exportMemoryTypesProduceInstantiableModule`) plus structural checks in
  `WasmExportCompilerTest` (export names present, `__ronto_alloc` emitted).
- The real round-trip was verified **out of band** with a Node host
  (`alloc -> write bytes -> call -> read bytes`), e.g.
  `shout("hello") => HELLO`, `rev('("a" "b" "c")') => ("c" "b" "a")`. That host
  harness is not in the repo / CI.

Weigh the priority against that: the residual value is only for Preview 1 hosts.

## What to add

A JS-host test that drives the Preview 1 memory ABI for real, run in CI:

1. Add a small Node (or browser) harness mirroring the session's `host.mjs`:
   instantiate the Preview 1 module with eight no-op `wasi_snapshot_preview1`
   stubs, call `__ronto_alloc`, write UTF-8 input, call the export, read the
   `(ptr,len)` result back. Assert `:string` and `:s-expr` round-trips.
2. It needs a JS WebAssembly host with WasmGC + i31 support (Node 22+ works).
   **Step 2's premise is stale (2026-09-12)**: no JS toolchain job is needed, and
   several tests already do this -- a JUnit test spawns `node` on a driver written
   to the temp dir and asserts on its stdout, gated by
   `@EnabledIf(... nodeIsAvailable)`. `WasmBytesBoundaryE2eTest` (the `:bytes`
   boundary), `WasmStringParamBoundaryE2eTest` (an import's memory-typed
   PARAMETERS, the same raw `(ptr,len)` ABI in the other direction),
   `WasmHostGlueE2eTest` and `WasmReentrantE2eTest` are the pattern to copy; what
   is left here is the EXPORT direction's `:string`/`:s-expr` round trip.
3. Keep the existing instantiate-only Testcontainers test as a cheap smoke check.

## Related

- Browser playground UX: `web/compile-run.html` already ships a "compile & run"
  page, but it drives modules through stdin + `(print (eval (read)))`, not the
  `wasm-export` memory ABI, and `RontoPlayground.java` exposes only
  `exportEval`/`exportCompileJvm`/`exportCompileWasm`/`exportPutFile`. Exposing
  "call an exported function" from the page is still open, and could share the
  harness from step 1.

## Touch points

- A new JS test + a new JS CI job.
- `src/web/java/am/ik/rontolisp/web/RontoPlayground.java` (optional playground
  integration).
- `WasmLispCompilerIntegrationTest` (`exportMemoryTypesProduceInstantiableModule`
  can point here for the full round-trip).
