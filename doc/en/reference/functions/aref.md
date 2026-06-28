# aref

`(aref array &rest subscripts)`

Returns the element of `array` at the given 0-based subscripts: one subscript for a rank-1 vector, two for a rank-2 array. To modify an element, use `aref` as a `setf` place: `(setf (aref array i j) value)`, which also works with `incf`/`decf`/`push`. `aref` is not exposed as a first-class function value (`#'aref` is unavailable), so call it directly.

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (setf (aref a 1) 9)
  (aref a 1)) ; => 9
```
