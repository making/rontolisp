# adjustable-array-p

`(adjustable-array-p array)`

Returns `t` when the array was created with [`make-array`](make-array.md) `:adjustable`, otherwise nil. The flag is reported verbatim; [`vector-push-extend`](vector-push-extend.md) grows any vector with a fill pointer regardless of it.

A string is a rank-1 array of characters, so it answers here too: a literal string is not adjustable, while a character vector built with `:adjustable` is.

```lisp
(adjustable-array-p (make-array 2 :adjustable t)) ; => T
(adjustable-array-p (make-array 2)) ; => NIL
(adjustable-array-p "abc") ; => NIL
(adjustable-array-p (make-array 2 :element-type 'character :adjustable t)) ; => T
```
