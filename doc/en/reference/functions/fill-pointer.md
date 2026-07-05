# fill-pointer

`(fill-pointer vector)`

Returns the fill pointer of a vector created with [`make-array`](make-array.md) `:fill-pointer`. The fill pointer is the vector's effective length: `length` and printing stop at it, while [`aref`](aref.md) can still reach the full backing storage. It is a `setf` place, so `(setf (fill-pointer v) n)` moves it to any position between 0 and the vector's total size. Signals an error when the array has no fill pointer (test with [`array-has-fill-pointer-p`](array-has-fill-pointer-p.md) first).

```lisp
(defparameter *v* (make-array 5 :fill-pointer 2 :initial-element 0))
(fill-pointer *v*) ; => 2
(setf (fill-pointer *v*) 4) ; => 4
(length *v*) ; => 4
```
