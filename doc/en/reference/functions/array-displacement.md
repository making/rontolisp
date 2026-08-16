# array-displacement

`(array-displacement array)`

Returns two values: the array `array` was displaced to with [`make-array`](make-array.md) `:displaced-to`, and the `:displaced-index-offset` it was created with. For a non-displaced array the values are nil and 0. The second value is only observable through a multiple-value consumer such as `multiple-value-bind`.

```lisp
(defparameter *base* (make-array 5 :initial-element 0))
(defparameter *view* (make-array 2 :displaced-to *base* :displaced-index-offset 3))
(multiple-value-bind (target offset) (array-displacement *view*)
  (list (eq target *base*) offset)) ; => (T 3)
(array-displacement *base*) ; => NIL
```
