# `wasm:export`: automated CI coverage for the `:string`/`:sexpr` memory ABI

**Status:** open. Follow-up to the `wasm:export` feature. Raised in the
`claude-opus` session 2026-06-28.

## Gap

The scalar designators (`:int`/`:float`/`:bool`) and the void path are covered
end-to-end in CI by `WasmLispCompilerIntegrationTest` via
`wasmtime --invoke <fn> ... <args>` (Testcontainers + wasmtime). The
**memory-backed `:string`/`:sexpr` designators are not** truly exercised in CI:

- `wasmtime --invoke` cannot write a pointer/length into linear memory, so the
  CLI can only confirm the module **instantiates and `_start` runs**
  (`exportMemoryTypesProduceInstantiableModule`) plus structural checks in
  `WasmExportCompilerTest` (export names present, `__ronto_alloc` emitted).
- The real round-trip was verified **out of band** with a Node host
  (`alloc -> write bytes -> call -> read bytes`), e.g.
  `shout("hello") => HELLO`, `rev('("a" "b" "c")') => ("c" "b" "a")`. That host
  harness is not in the repo / CI.

## What to add

A JS-host test that drives the memory ABI for real, run in CI:

1. Add a small Node (or browser) harness mirroring the session's `host.mjs`:
   instantiate the Preview 1 module with eight no-op `wasi_snapshot_preview1`
   stubs, call `__ronto_alloc`, write UTF-8 input, call the export, read the
   `(ptr,len)` result back. Assert `:string` and `:sexpr` round-trips.
2. Wire it into the **web-playground CI job** (which already has a JS toolchain)
   rather than the Java Testcontainers path, since it needs a JS WebAssembly host
   with WasmGC + i31 support (Node 22+ works).
3. Keep the existing instantiate-only Testcontainers test as a cheap smoke check.

## Related

- Browser playground UX: expose "call an exported function" in
  `src/web/java/.../RontoPlayground.java` so the demo can show `wasm:export` from
  the page (currently the playground only returns the compiled WASM blob). This
  could share the harness from step 1.

## Touch points

- A new JS test + the web-playground CI job config.
- `src/web/java/am/ik/rontolisp/web/RontoPlayground.java` (optional playground
  integration).
- `WasmLispCompilerIntegrationTest` (the current instantiate-only memory test
  can point here for the full round-trip).
