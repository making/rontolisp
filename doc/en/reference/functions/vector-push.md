# vector-push

`(vector-push value vector)`

Stores `value` at the fill pointer of a vector created with [`make-array`](make-array.md) `:fill-pointer`, increments the fill pointer and returns the index the value was stored at, or nil (leaving the vector untouched) when the vector is already full. Use [`vector-push-extend`](vector-push-extend.md) to grow the vector instead. Signals an error when the vector has no fill pointer.

```lisp
(defparameter *v* (make-array 2 :fill-pointer 0))
(vector-push 10 *v*) ; => 0
(vector-push 20 *v*) ; => 1
(vector-push 30 *v*) ; => NIL
*v* ; => #(10 20)
```
