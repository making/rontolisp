# with-simple-restart

`(with-simple-restart (restart-name format-control format-arg...) body...)`

Sugar over [`restart-case`](restart-case.md): evaluates `body...` with a restart named `restart-name` established; invoking it returns `(values nil t)` from the `with-simple-restart` form, so the caller can tell "the body was abandoned" from "the body returned nil". The format control becomes the restart's report (lite: the format arguments are accepted and dropped — nothing renders reports). On normal completion the body's values are returned. Supported on every backend except `--no-gc`.

```lisp
(handler-bind ((error (lambda (c) (invoke-restart 'skip))))
  (multiple-value-list
   (with-simple-restart (skip "Skip the failing step.")
     (error "step failed")))) ; => (NIL T)
```
