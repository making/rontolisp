# m79. `wit-export`: compound types on a `--no-wasi` module (the glue lifts and lowers)

Difficulty: Medium

A component's export carries every WIT type but flags, a handle, a stream and a future
(`.kb/wit.md`, "Compound types across an export"). A `--no-wasi` core module refuses such a
world at its WIT line (`WasmLispCompiler`, "carries a type only a component's export does"),
while its IMPORT side already crosses the canonical ABI: `--emit-js-glue` lifts and lowers in
JavaScript from `CanonicalLayout` descriptors (`codegen/wasm/WitGlueLayouts`,
`compiler/HostGlueEmitter`; `.kb/wasm-import.md`, "A WIT interface beyond the flat set").

- **The module half exists**: `ExportMarshal` takes `cabiReallocFuncIndex = -1` for a core
  module (the result's area from `__ronto_alloc` behind an aligned heap top, no post-return);
  the wrapper's core signature is `WitExportSignature.coreParams` / `coreResults`.
- **To make**: the glue's `exported(...)` for a canonical export -- lower the arguments
  (`lowerFlat` / `store`, the staging through `__ronto_alloc` inside the mark/reset bracket),
  call, lift the answer (`liftFlat` / `load`) BEFORE the reset; jco's value conventions as the
  import side has them; a top-level `result`'s error arm thrown as `WitError`.
- **Decide**: the export's JavaScript name (the label, camelCased as jco does, or as written
  like a flat export's), and whether a Preview 1 WASI module keeps the refusal (its host is
  wasmtime, which calls no canonical core export).
- Pins to extend: `WasmHostGlueE2eTest.anInterfaceTheFlatSetDoesNotCarryCrossesThroughTheCanonicalAbi`,
  `HostGlueEmitterTest`, `WitExportInlinerTest.aWorldIsRefusedAnOutputThatCannotExportIt` (the
  `--no-wasi` leg flips); `doc/{en,ja}/reference/functions/rontolisp-wit-export.md`,
  "Compound types".
