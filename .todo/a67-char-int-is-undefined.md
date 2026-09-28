# char-int is undefined on every backend

Difficulty: Low

Measured 2026-09-28: `(char-int #\a)` is "The function CHAR-INT is undefined" on the interpreter,
and a compile warning plus the same call-time error on the JVM and wasm-GC.

CL's `char-int` answers a non-negative integer encoding the character; with no implementation-
defined attributes it can be the code point, as `char-code` answers. Add it on all four backends
(`.kb/adding-primitives.md`), with a non-character reporting `CHAR-INT: The value 1 is not of type
CHARACTER` like the other character built-ins (`.kb/error-handling.md`, "A character built-in
checks its argument"): a `CHAR-INT` row in `OperandTypes`' `CHARACTER_BUILTINS`, and the docs
pages en/ja.
