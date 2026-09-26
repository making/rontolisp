# A list walk over a non-list and a char index keep unnamed, diverging errors

Difficulty: Medium

972 named `car`/`cdr`, the `nthcdr`/`aref` subscripts and the numeric accessors
(`compiler/OperandTypes`). Still unnamed, measured 2026-09-26:

- `(nthcdr 1 5)` (and so `(nth 1 5)`, `(second 5)`): interpreter answers `NIL`, JVM the
  pad's generic `the value is not of the expected type`, wasm an uncatchable trap (the
  walk's own `ref.cast`).
- `(dolist (x 5) x)`: `NIL` on the interpreter and the JVM, a trap on wasm.
- `(char "ab" nil)` / `schar`: interpreter `CHAR expects an integer index, got: NIL` (a
  simple-error), JVM a raw `NullPointerException` text, wasm a trap.

CL: the list operators signal a `type-error` for a non-list (`LIST`), the index a
`type-error` (`INTEGER`). Goal: `NTHCDR: The value 5 is not of type LIST`, `CHAR: The value
NIL is not of type INTEGER` and the like, identically on all four backends -- extend
`OperandTypes` (both are funnel-typed) and reuse the 972 checks (`_ckIdx` on the JVM,
`_idx_chk` on wasm). Decide `dolist`'s CL-conformant answer first (SBCL signals).
