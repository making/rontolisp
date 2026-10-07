# e20. A non-top-level `(defun (setf name) ...)` crashes the compile paths

Difficulty: Medium

```lisp
(let ((k 1))
  (defun (setf zz-n) (v x) (list v x k)))
(print (setf (zz-n 1) 2))
(defun zz-def () (defun (setf zz-m) (v x) (list v x)))
(zz-def)
(print (funcall #'(setf zz-m) 1 2))
```

SBCL 2.2.9 and the interpreter: `(2 1 1)`, `(1 2)`. JVM, P1 and component: the compile fails
with a raw `class LispCons cannot be cast to class LispSymbol` (`while compiling defun ZZ-DEF`
on the JVM). A top-level one is renamed to its `%setf-` writer by
`expandTopLevelDefinitions`; a `defun` under a `let` (let over defun) or inside a function body
reaches the nested-defun lowering with the `(setf name)` list as its name.

## Plan

- Four-backend fixture first (let over a setf defun, one inside a function body, a
  `defmethod (setf ...)` under a `let`, each used as a place, through `#'` and `fdefinition`).
- Rename the nested definition the way the top-level one is (`setfFunctionName`) and register
  its place, wherever the compile paths collect nested defuns (`.kb/defstruct.md`,
  "setf on accessors, and setf-functions").
