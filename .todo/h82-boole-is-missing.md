# h82. `boole` is missing

Difficulty: Low

`(boole boole-and 12 10)` -> `The function BOOLE is undefined`; the `boole-*` constants exist.
`BOOLE` is not even a `cl` symbol: a program-level `(defun boole ...)` inside `cl-qrencode` binds
`CL-QRENCODE::BOOLE`. Also counted in `.todo/715` (13 ANSI cases).

Blocks cl-qrencode (`rs-ecc/galois.lisp:29`). With a user-supplied `boole` the interpreter encodes
`"hello"` into a 21x21 matrix, as SBCL does. Implement on all four backends
(`.kb/adding-primitives.md`); the 16 operations reduce to `logand`/`logior`/`logxor`/`lognot`
compositions.
