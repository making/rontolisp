# e23. A computed name and `fmakunbound` of a nested `defun` miss its global

Difficulty: Medium

```lisp
(let ((n 0)) (defun zz-c () (incf n)))
(print (handler-case (funcall (intern "ZZ-C")) (error (c) (type-of c))))
(print (fboundp (intern "ZZ-C")))
(print (handler-case (functionp (symbol-function (intern "ZZ-C"))) (error (c) (type-of c))))
(print (fmakunbound 'zz-c))
(print (handler-case (zz-c) (undefined-function (c) (cell-error-name c))))
```

SBCL 2.2.9 and the interpreter: `1`, `T` (SBCL the function), `T`, `ZZ-C`, `ZZ-C`. JVM, P1 and
component (measured 2026-10-07): `UNDEFINED-FUNCTION`, `NIL`, `UNDEFINED-FUNCTION`, `ZZ-C`, `1` (the
call still runs).
A defun below the top level is a global variable holding the closure
(`.kb/core-representation.md`, "The global is the function's bound-ness"): the literal
references read it, but a name resolved at run time goes to `_fenv` and the compiled-function
registry, which never learn it, and `fmakunbound` writes a tombstone the call through the
global never reads.

## Plan

- Four-backend fixture first (computed `funcall` / `apply` / `fboundp` / `symbol-function` /
  `fdefinition` of plain and `(setf name)` nested defuns, before and after; `fmakunbound`
  then a call, `#'` and `fboundp`; redefinition after `fmakunbound`).
- Let the run-time name lookups consult the nested-defun globals (only in a program that has
  nested defuns and resolves names at run time), and let `fmakunbound` of such a name clear the
  global.
