# e12. `(setf (name args...) v)` of an undefined `name` is refused instead of calling `(setf name)`

Difficulty: Medium

```lisp
(print (handler-case (setf (zz-t 3) 5)
         (undefined-function (c) (cell-error-name c))))
```

SBCL: `(SETF ZZ-T)` at run time (the setf function is looked up when the form runs). All four
backends: `setf does not support place: ZZ-T`, at expansion, so a `(defun (setf zz-t) ...)` loaded later
(or defined in a branch) can never serve the call. Decide whether an unknown operator in place position
should expand to the `%setf-` writer call (late-bound, undefined-function at run time, with the
compile-time warning of any undefined call) as CL does, or keep the early refusal as a typo guard; the
refusal's message is what a misspelled accessor currently gets, so the change needs the `.kb` setf
entries read first.

## Plan

- Measure which existing tests and examples depend on the refusal text.
- If the late-bound expansion is taken, pin it on all four backends together with the
  undefined-function name `(setf name)`.
