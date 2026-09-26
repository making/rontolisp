# A string access's non-string or a (setf char) index keep unnamed, diverging errors

Difficulty: Medium

980 named `char`/`schar`'s subscript. Still unnamed, measured 2026-09-26:

- `(setf (char s nil) #\x)`: interpreter `%SCHAR-SET expects an integer index, got: NIL` (a
  simple-error), JVM a raw `NullPointerException` text, wasm a trap. The compiled backends lower
  it through `expandScharSetFunctional` to the shared `%schar-set-runtime` defun, so the check
  has to sit at the site (under a `(SETF CHAR)` name, `%SCHAR-SET` in `OperandTypes.REWRITTEN`)
  or the defun's index read reports under whatever operator it runs in.
- `(char 5 0)`: interpreter `CHAR expects a string, got: 5` (a simple-error), JVM the pad's
  generic `the value is not of the expected type`, wasm a trap.

Goal: `(SETF CHAR): The value NIL is not of type INTEGER`, `CHAR: The value 5 is not of type
STRING` as catchable type-errors, identically on all four backends (`.kb/error-handling.md`,
"A wrong-type argument names its operator"). `STRING` is a new `OperandTypes.Kind`.
