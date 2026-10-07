# e14. A `defstruct` slot accessor has no `(setf name)` function

Difficulty: Medium

```lisp
(defstruct zz-s b)
(print (fboundp '(setf zz-s-b)))
(let ((o (make-zz-s :b 1)))
  (funcall #'(setf zz-s-b) 6 o)
  (print (zz-s-b o)))
```

SBCL 2.2.9: true, then `6`. Interpreter, JVM, P1 and component: `NIL`, then `The function (SETF
ZZ-S-B) is undefined` (a trap on wasm outside EH mode). `(setf (zz-s-b o) 6)` works everywhere: the
place is expanded inline. A `defclass` `:accessor` already has its `%setf-` writer, so
`#'(setf acc)` and `(fdefinition '(setf acc))` work for it.

## Plan

- Four-backend fixture first (`#'(setf slot)`, `fdefinition`, `fboundp`, `apply`, a `:read-only`
  slot staying undefined).
- Decide where the writer is defined (beside the reader the struct expansion emits, or on first
  reference only, so a program that never takes the function pays nothing) after reading
  `.kb/defstruct.md`.
