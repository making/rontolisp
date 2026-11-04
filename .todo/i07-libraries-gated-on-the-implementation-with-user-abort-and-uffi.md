# i07. Libraries gated per implementation: `with-user-abort`, `uffi`

Difficulty: Low

Measured 2026-11-04. Neither library has a generic branch, so neither can name rontolisp without
upstream or one of the substitution tiers (`.kb/asdf.md`).

- **`with-user-abort`** (`main.lisp:6`) returns the implementation's interrupt condition per
  `#+sbcl sb-sys:interactive-interrupt` and friends. With no branch the form is `(quote)`:
  `the value is not of the expected type`. Consumer: clingon. With a `#+rontolisp` branch added in the walked copy, clingon's `make-command`, `parse-command-line`,
  `print-usage` and `run` match SBCL on the interpreter. Decide what `user-abort` means: a Tier 4
  rewrite naming a real rontolisp condition, signalled on SIGINT where a backend can receive it.
  Check for an existing interrupt condition first.
- **`uffi`**: `uffi.asd` guards its whole `defsystem` with `#+(or allegro ... sbcl scl)`, so here the
  file defines nothing: `uffi.asd does not define system 'uffi'`. Consumer: clsql (`clsql.asd:32`).
  clsql has its own alternative: `--feature clsql-cffi` routes it to `cffi-uffi-compat`, which
  loads on the interpreter since 2026-11-04. Make `uffi` a Tier 2 override
  that depends on `cffi-uffi-compat`, or announce the feature; measure clsql's own `.asd` first,
  as it is unmeasured past that point. Its SQL backends also load foreign libraries.
