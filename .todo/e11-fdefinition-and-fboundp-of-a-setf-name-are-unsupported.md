# e11. `(fdefinition '(setf name))` and `(fboundp '(setf name))` are unsupported

Difficulty: Medium

```lisp
(defun (setf zz-d) (v x) (list v x))
(print (funcall (fdefinition '(setf zz-d)) 1 2))
(print (fboundp '(setf zz-d)))
```

SBCL: `(1 2)`, then `T`-like true (a function object in 2.2.9). Interpreter:
`FDEFINITION expects a symbol, got (SETF ZZ-D)`. JVM: a raw `ClassCastException` (`Object[]` to
`String`). P1 and component: an `unreachable` trap. `#'(setf name)` already resolves through the
`%setf-` writer convention (`LispMacroExpander.setfFunctionName`), so the symbol-taking operators
(`fdefinition`, `fboundp`, `fmakunbound`, `(setf (fdefinition ...))`) need to take the list form and map
it to that name on every backend, and report a missing one as `(setf name)`.

## Plan

- Four-backend fixture first, one operator at a time.
- Map a `(setf name)` argument to the writer's stored name where each operator reads its
  designator.
