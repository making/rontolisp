# c23. The compiled runtime readers read a trailing-dot integer as a float

Difficulty: Low

Measured 2026-10-03: `(print (read-from-string "5."))` prints `5` on the interpreter (Common
Lisp's reading: a trailing `.` marks a decimal integer) and `5.0` on the JVM and both WASM
backends; `-5.` likewise. A `5.` in source compiles to the integer 5 everywhere -- only the
emitted readers diverge. `doc/{en,ja}/guides/read-load-limitations.md` lists `5.` among the
float tokens, so the divergence is documented rather than chosen.

## Plan

- `JvmReadRuntimeBuilder` (its float classifier before `parseDouble`) and
  `WasmReadRuntimeBuilder.emitTryInteger`/`emitTryFloat`: digits followed by one `.` and nothing
  else read as the integer, at any magnitude (`123456789012345678901234567890.` too).
- Check the frontend's other trailing-dot forms (`1.e5` is a float; `+5.`, `-5.`) and match them.
- Update `doc/{en,ja}/guides/read-load-limitations.md`.

## Pin

- ci-spec: `read-from-string` of `5.`, `-5.`, `+5.`, a limb-sized `N.`, `1.e5`, all four backends.
