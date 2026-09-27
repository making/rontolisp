# #'aref skips the rank and per-axis subscript checks on the compiled backends

Difficulty: Low

Measured 2026-09-27, `m` = `(make-array '(2 2) :initial-contents '((1 2) (3 4)))`:

| Form | Interpreter | JVM, wasm P1, component |
| --- | --- | --- |
| `(apply #'aref m '(0 2))` | `AREF: The value 2 is not of type (INTEGER 0 (2))` | `3` |
| `(apply #'aref m '(1))` | `aref: expected 2 subscripts, got 1` | `2` |

`(aref m 0 2)` in call position reports the out-of-range subscript on all four. The compiled
`#'aref` is `BuiltinFunctionWrappers.arefFoldBody`: a Horner fold of the subscript list over
`array-dimensions` and one `row-major-aref`, which checks only the total size and never the
subscript count. Goal: the fold checks the count against the rank and each subscript against its
own dimension, reporting what the call does (`.kb/error-handling.md`, "An out-of-range subscript
is a type-error naming its bound"); `#'array-row-major-index` shares the fold.
