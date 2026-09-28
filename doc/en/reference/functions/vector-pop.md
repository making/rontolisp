# vector-pop

`(vector-pop vector)`

Decrements the fill pointer of a vector created with [`make-array`](make-array.md) `:fill-pointer` and returns the element it moved past (the last pushed element). A vector without a fill pointer signals a `type-error` ([`fill-pointer`](fill-pointer.md)); a fill pointer that is already 0 signals a `simple-error`, `VECTOR-POP: there is nothing left to pop`.

```lisp
(defparameter *v* (make-array 3 :fill-pointer 0))
(vector-push 10 *v*) ; => 0
(vector-push 20 *v*) ; => 1
(vector-pop *v*) ; => 20
(length *v*) ; => 1
```
