# h32. `makunbound` and `compiler-macro-function` are undefined

Difficulty: Low

Found 2026-10-30 probing Quicklisp libraries.

- `makunbound`: undefined (`log4cl` `src/logger.lisp:825`).
- `compiler-macro-function`: undefined. `documentation-utils` (`toolkit.lisp:110`,
  `#'compiler-macro-function` as a value) fails, and with it every Shinmera library that
  depends on it: `plump`, `parachute`, `lquery`, `clss` ...  Measured: with a stub
  `(defun compiler-macro-function (n &optional e) nil)`, documentation-utils loads and a
  parachute `define-test` + `test` run passes on the interpreter.

Both are standard CL. `compiler-macro-function` should answer the function
`define-compiler-macro` registered (`.kb/compiler-macros.md`), nil otherwise; `setf` of it
too if cheap. `makunbound` on a special makes `boundp` nil and a read signal
`unbound-variable` (`.kb/dynamic-special-variables.md` already has the unbound marker).
All four backends; follow `.kb/adding-primitives.md`.
