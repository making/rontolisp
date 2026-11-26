# m90. `wit-export`: an `async func` of compound types

Difficulty: High

`WitExportDirective.exportForms` refuses a compound type in an `async func` at the WIT line
("an async export carries the flat types only"); `shapeOf` makes the same refusal for the
front ends.

- How a flat async export delivers its value: `.kb/wasi-component.md`, "`:async t` I/O
  exports". A result in memory has to outlive the wrapper there too: the canonical export's
  area is popped by `cabi_post_*` (`.kb/wit.md`, "Compound types across an export",
  "Memory"), and an async lift has no post-return.
- The serve handler's `task.return` lowering (`TaskReturn`) is the precedent for lowering a
  compound result mid-task.
- Parameters past `MAX_FLAT_PARAMS_ASYNC` (4) arrive as one pointer (`WitCanonicalAbi`).
- The Java side of a flat async export: `JavaWitImportTest.aClassImplementsTheWorldAComponentExports`.
- Measure with wasmtime's `--invoke`, jco and a `bindgen!` host, as the sync exports were
  (`.kb/wit.md`, "Hosts measured").
