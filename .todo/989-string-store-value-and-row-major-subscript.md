# A string store's non-character value and row-major-aref's subscript keep unnamed, diverging errors

Difficulty: Medium

983 named a string access's non-string and a `(setf char)` subscript. Still open, measured
2026-09-26:

- `(setf (char s 0) 5)` / `(setf (aref s 0) 5)` on a string `s`: interpreter
  `%SCHAR-SET expects a character, got: 5` (a simple-error); JVM stores it, and printing `s`
  then escapes as an uncaught `ClassCastException` (`Long` to `[I`); wasm traps.
- `(row-major-aref v nil)` / `(setf (row-major-aref v nil) 0)` on a vector: interpreter the
  unnamed `The value NIL is not of type INTEGER` type-error; JVM a `NullPointerException`
  text; wasm a trap. 983 left the string arm of a `row-major-aref` place unnamed to match.

Goal: `(SETF CHAR): The value 5 is not of type CHARACTER` (a new `OperandTypes.Kind`; the
value goes through `%schar-set-runtime`, so the check sits at the site like
`%check-string`), and `ROW-MAJOR-AREF` / `(SETF ROW-MAJOR-AREF)` naming the subscript, as
catchable type-errors identically on all four backends (`.kb/error-handling.md`, "A
wrong-type argument names its operator", "String accesses").
