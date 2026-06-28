# gethash

`(gethash key table &optional default)`

Looks up `key` in `table` and returns the associated value, or `default` (nil when omitted) if the key is absent. Keys are compared structurally as if by `equal`, so an equal list, string, or number key matches. To store a value, use `gethash` as a `setf` place: `(setf (gethash key table) value)`, which also cooperates with `incf`/`decf`/`push`.

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (gethash 'a h)) ; => 1
```
