# with-simple-restart

`(with-simple-restart (restart-name format-control format-arg...) body...)`

Sugar over [`restart-case`](restart-case.md): evaluates `body...` with a restart named `restart-name` established; invoking it returns `(values nil t)` from the `with-simple-restart` form, so the caller can tell "the body was abandoned" from "the body returned nil". The format control and its arguments become the restart's report; the arguments are evaluated only when the report prints, which only the debugger of [`rontolisp --swank`](../../getting-started/swank.md) does. On normal completion the body's values are returned. Supported on every backend except `--no-gc`.

```lisp
(handler-bind ((error (lambda (c) (invoke-restart 'skip))))
  (multiple-value-list
   (with-simple-restart (skip "Skip the failing step.")
     (error "step failed")))) ; => (NIL T)
```
