# find-restart

`(find-restart identifier [condition])`

Returns the innermost active restart named `identifier` (symbol or keyword) as a first-class restart object, or `nil` when none is active; a restart object passes through unchanged. The object can be passed to [`invoke-restart`](invoke-restart.md) and read with [`restart-name`](restart-name.md). Lite: the optional `condition` argument is accepted and ignored (restarts are not associated with conditions).

```lisp
(restart-case
    (let ((r (find-restart 'retry)))
      (list (null r) (restart-name r)))
  (retry () nil)) ; => (NIL RETRY)
```
