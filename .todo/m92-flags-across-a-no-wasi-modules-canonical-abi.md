# m92. `flags` across a `--no-wasi` module's canonical ABI

Difficulty: Medium

A component carries `flags` both ways (a list of keywords; `WasmComponentImportCompiler`
`emitLowerFlags` / `emitLiftFlags`). A `--no-wasi` core module that imports an interface
through the canonical ABI refuses a member carrying one, in the compiler and without the
WIT line (`WitGlueLayouts.layout`): its JavaScript glue (`HostGlueEmitter`,
`CanonicalLayout`) has no value for flags.

- The JavaScript value: the glue's other values follow jco (a record an object, an enum
  its name, a variant `{ tag, val }`). Measured 2026-11-26, jco 1.42.0 on a Lisp component
  exporting flags: an object of booleans, every label present in an answer
  (`{"read":true,"write":false,"exec":true}`), each optional in an argument
  (`{ read: true }`); a label of several words was not measured (a record's field is
  camelCased).
- A `CanonicalLayout` for it (the labels, the size `WitCanonicalAbi.flagsSize`), and the
  glue's four places a value of cases is read or written (`load`, `store`, `liftFlat`,
  `lowerFlat`).
- Refuse at the WIT line until then: `WitImportDirective.validateComponentParam` /
  `validateComponentResult` do not know the output is a core module.
- The export direction of a core module is `.todo/m79`'s.
- Pin: `WasmHostGlueE2eTest` (node).
