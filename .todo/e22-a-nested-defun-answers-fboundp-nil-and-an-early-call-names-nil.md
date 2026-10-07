# e22. A nested `defun` answers `fboundp` NIL, and a call before it runs names NIL

Difficulty: Medium

```lisp
(defun zz-def () (defun zz-q () 1) (defun (setf zz-q) (v) v))
(print (handler-case (zz-q) (error (c) (list (type-of c) (cell-error-name c)))))
(print (handler-case (setf (zz-q) 1) (error (c) (list (type-of c) (cell-error-name c)))))
(zz-def)
(print (list (zz-q) (fboundp 'zz-q) (fboundp '(setf zz-q))))
```

SBCL 2.2.9 and the interpreter: `(UNDEFINED-FUNCTION ZZ-Q)`, `(UNDEFINED-FUNCTION (SETF ZZ-Q))`,
then `(1 #<FUNCTION ZZ-Q> #<FUNCTION (SETF ZZ-Q)>)` / `(1 T T)`. JVM, P1 and component:
`(UNDEFINED-FUNCTION NIL)` twice, then `(1 NIL NIL)`. A defun below the top level is a global
variable holding the closure (`.kb/core-representation.md`): a literal `fboundp` folds it to NIL
(it is in neither `functions` nor `userDefunNames`), and a call before the definition runs reads
the unbound global, whose report carries no name. Same for a let-over-defun.

## Plan

- Four-backend fixture first (plain and `(setf name)` nested defuns, under a `let` and in a
  function body; `fboundp` before and after, a call and a place before).
- A literal `fboundp` of a nested-defun name tests the global's bound-ness; a call or `#'` of one
  whose global is unbound signals `undefined-function` naming the function (`(SETF NAME)` for a
  `%setf-` writer), not the variable.
