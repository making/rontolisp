# eval-when

`(eval-when (situation...) body...)`

Evaluates the body as a `progn`, treating every situation (`:compile-toplevel`, `:load-toplevel`, `:execute`, and the deprecated `compile`/`load`/`eval` spellings) as "evaluate now". The situation list is required but otherwise ignored, so code that runs under real Common Lisp situation rules also runs here.

At top level the body forms are spliced into the surrounding program on the compile path, so the common macro-exporting idiom -- a `defmacro` wrapped in `(eval-when (:compile-toplevel :load-toplevel :execute) ...)` -- defines the macro for the rest of the compilation unit, and nested `defun`s are collected like ordinary top-level definitions.

```lisp
(progn
  (eval-when (:compile-toplevel :load-toplevel :execute)
    (defun ew-double (x) (* x 2)))
  (ew-double 21)) ; => 42
```
