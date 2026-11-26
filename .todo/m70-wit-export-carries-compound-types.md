# m70. `wit-export` carries compound types: records, lists, options, variants, results

Difficulty: High

A world's export carries the integers, `f64`, `bool` and `string` alone: `compiler/BoundaryType`
is the export subset, `WitExportDirective` refuses any other type at its WIT line, and the front
ends inherit the refusal -- Lisp's `rontolisp:wit-export`, Clojure's, Java's `@WitExport`
(`doc/en/java/wit.md`, "Limitations"; `.kb/wit.md`, "The contract checks"). The import side
already lifts and lowers every type but `flags` on a component and a `--no-wasi` module
(`WasmComponentImportCompiler`), which is why `@WitImport` maps them in Java alone.

The user's decision (2026-11-26): support the export side from the bottom up -- the canonical
ABI in the export path first, then each front end's mapping.

- **The ABI**: a result that does not flatten to one value is written to linear memory and its
  address returned, with a `cabi_post_*` to release it (today only a string-returning export has
  such a shim, `.kb/wasi-component.md`); a parameter past the flat limit is read from memory. The
  direction pairs with the import side: an export's result is lowered as an import's argument
  is, an export's parameter lifted as an import's result is. Measure how much of
  `WasmComponentImportCompiler` the export path can share before writing a second copy.
- **Backends**: wasm-GC and `--no-gc`, a component and a `--no-wasi` module; a Preview 1 core
  module keeps the flat set (state the refusal).
- **Front ends**: Lisp `wit-export` (the value a Lisp function answers for a record, a variant,
  a `result` -- follow `wit-import`'s settled mapping, `.kb/wit.md`), Clojure, Java `@WitExport`
  (records, sealed interfaces, enums, `List`, `Optional`, `byte[]` as `@WitImport` maps them; a
  `result` as `am.ik.rontolisp.wit.WitResult`, which m69 added for `@WitImport`). A provider
  to try a component against without CI: a Rust crate on wit-bindgen 0.62 (in `~/.cargo`,
  builds `--offline` for `wasm32-unknown-unknown`), `wasm-tools component new`, then
  `wasm-tools compose app.wasm -d provider.wasm` (m69 ran its result program so).
- **Hosts**: wasmtime's WAVE invoke and a `bindgen` host (`WitOracleE2eTest`), jco
  (`.kb/wasi-component.md` on jco's export-lift reading).

Split it where a measurement says so (the ABI and Lisp first, the other front ends after), and
record the split in this item rather than forcing one change through. Pins:
`JavaWitImportTest.aClassImplementsTheWorldAComponentExports`, `WitOracleE2eTest`, the
`wit-export` contract tests; `doc/{en,ja}` for each front end's page.
