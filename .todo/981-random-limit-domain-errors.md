# random of a ratio or a non-positive limit diverges by backend

Difficulty: Low

972 made a non-real limit `RANDOM: The value X is not of type REAL` everywhere. A real
outside CL's `(or (integer 1) (float (0)))` still diverges (measured 2026-09-26):

- `(random 1/2)`: interpreter a simple-error `random expects an integer or float limit,
  got: 1/2`, JVM answers `0`, wasm the unnamed `The value 1/2 is not of type INTEGER`
  (EH mode) or a trap.
- `(random -1)`: interpreter `random expects a positive limit, got: -1`, JVM `0`, wasm a
  huge integer.

Goal: one catchable report on all four backends. The CL type is a compound
(`(OR (INTEGER 1) (FLOAT (0.0)))`), which the wasm landing cannot build as a
`type-error-expected-type` today (its type symbols are interned names,
`WasmOperandTypes.Texts`); either teach it a compound or choose a documented symbol.
