# Internal (body-level) `define-record-type` for the Scheme front end

Difficulty: Medium

Split off from `.todo/826` (the internal `define-record-type` row). Today a
`define-record-type` anywhere but the top level is refused by name
(`SchemeLowering.lowerForm`: "define-record-type is only supported at the top level"), and
the compile path refuses a non-top-level `defstruct` (`.kb/defstruct.md`).

## Scope

- A `define-record-type` in a body (R7RS 5.3.2, 5.5): its procedures are local to the body,
  letrec*-style like an internal `define`, and shadow outer bindings.
- Lower to core forms only: the `defstruct` stays top-level (hoisted, under a name no user
  identifier can mangle to, unique per occurrence); the body binds the local names to it.
  No backend change; a program without one compiles to byte-identical output (measure).
- Macros: the expander renames the record's local names like any internal definition.
- Interpreter, JVM, both WASM backends; the REPL session; a library body.
- Generativity: R7RS leaves it unspecified; Gauche makes a new type per evaluation. State
  what this front end does.

## Test plan

- Failing first: `SchemeLoweringTest` (lowering shape, shadowing, errors), a
  `scheme-spec.yaml` case on all four backends against `gosh -r7`.
- `.kb/scheme-frontend.md`, the reference page for `define-record-type` (doc/en + doc/ja).
