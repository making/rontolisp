# gethash

`(gethash key table &optional default)`

Looks up `key` in `table` and returns the associated value, or `default` (nil when omitted) if the key is absent. Keys are compared structurally as if by `equal`, so an equal list, string, or number key matches. To store a value, use `gethash` as a `setf` place: `(setf (gethash key table) value)`, which also cooperates with `incf`/`decf`/`push`. The place's `key`, `table` and `default` are evaluated left to right, then the value; the `default` of a write is evaluated but never stored. Under [`multiple-value-bind`](../macros/multiple-value-bind.md) (and the other multiple-value consumers) a `gethash` call also supplies a second present-p value distinguishing a stored nil from a missing key. The function object `#'gethash` takes the same optional default and answers the same two values. A `table` that is no hash table signals a `type-error` whose expected type is `hash-table`: `GETHASH: The value 5 is not of type HASH-TABLE`.

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (gethash 'a h)) ; => 1
```

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) nil)
  (multiple-value-bind (v present-p) (gethash 'a h)
    (list v present-p))) ; => (NIL T)
```
