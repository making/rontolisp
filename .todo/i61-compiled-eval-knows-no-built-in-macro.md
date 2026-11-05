# i61. Compiled `eval` knows no built-in macro but the ones with an arm

Difficulty: Medium

`_eval` has hand-written arms for `let`, `cond`, `when`, `setq`, `dotimes` and a few more; every other built-in macro
exists only in the compile-time expander. Measured 2026-11-05: the interpreter and SBCL 2.2.9 answer these, and the JVM
and both WASM backends signal `undefined-function` naming the macro:

```lisp
(eval '(let* ((a 1) (b a)) b))
(eval '(incf x))
(eval '(defvar *v* 1))
(eval '(dolist (x '(1)) x))
(eval '(multiple-value-list (values 1 2)))
```

Before signalling (`.kb/eval-runtime.md`) these answered a silent NIL, so a file a run-time `load` reads and that opens
with `(defvar *x* 1)` used to go on and read the symbol.

## Plan

Do not write an arm per macro twice. One Lisp-source table of run-time expansions (`let*`, `do`, `do*`, `dolist`,
`return`, `incf`, `decf`, `defvar`, `defparameter`, `defconstant`, `multiple-value-list`, `multiple-value-bind`,
`case`, `ecase`, `typecase`, `etypecase`, `prog1`, `with-open-file` ...) spliced beside the eval runtime, reached from
`_eval`'s unknown-operator path on both backends: an operator the table holds expands and evaluates again, any other
reports as it does now. `defvar` needs the bound-ness test `%global-access` makes hard (a compiled special without a
value throws through it), and a special defined at run time binds lexically under `_eval`'s `let`.
