# eval-when

`(eval-when (situation...) body...)`

Whether the body runs depends on where the form stands (CLHS 3.2.3.1). The situations are `:compile-toplevel`, `:load-toplevel`, `:execute`, and the deprecated `compile`/`load`/`eval` spellings.

**At top level** -- a form of the program or of a file it loads, a member of a top-level `progn`, `locally`, `macrolet`, `symbol-macrolet` or `eval-when`, or what a macro call there expands to -- any situation runs the body: a program stands for both its compile and its load. An empty situation list names none and runs nothing.

The body forms are spliced into the surrounding program on the compile path, so the common macro-exporting idiom -- a `defmacro` wrapped in `(eval-when (:compile-toplevel :load-toplevel :execute) ...)` -- defines the macro for the rest of the compilation unit, and nested `defun`s are collected like ordinary top-level definitions. A form under `:compile-toplevel` also runs at compile time, for the macros after it. A macro call that expands to an `eval-when` itself takes `compile-file`'s reading on the compile path: `:compile-toplevel` runs the body at compile time only, `:load-toplevel` keeps it in the program, and `:execute` alone drops it.

**Anywhere else** -- a function body, a `let`, a form [`eval`](../functions/eval.md) runs -- only `:execute` (or `eval`) runs the body, on every backend, and the form answers `nil` otherwise.

```lisp
(progn
  (eval-when (:compile-toplevel :load-toplevel :execute)
    (defun ew-double (x) (* x 2)))
  (ew-double 21)) ; => 42
(let () (eval-when (:load-toplevel) 1)) ; => NIL
(let () (eval-when (:execute) 2)) ; => 2
```
