# adjustable-array-p

`(adjustable-array-p array)`

Returns `t` when the array was created with [`make-array`](make-array.md) `:adjustable`, otherwise nil. The flag is reported verbatim; [`vector-push-extend`](vector-push-extend.md) grows any vector with a fill pointer regardless of it.

```lisp
(adjustable-array-p (make-array 2 :adjustable t)) ; => T
(adjustable-array-p (make-array 2)) ; => NIL
```
