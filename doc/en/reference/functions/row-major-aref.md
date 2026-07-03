# row-major-aref

`(row-major-aref array index)`

Returns the element of `array` at the given 0-based flat row-major `index`, independent of the array's rank -- element `(i, j)` of a 2x3 array is at flat index `i * 3 + j`. Use [`array-row-major-index`](array-row-major-index.md) to compute the flat index of a set of subscripts. To modify an element, use `row-major-aref` as a `setf` place: `(setf (row-major-aref array k) value)`. Like `aref`, it is not exposed as a first-class function value, so call it directly.

```lisp
(let ((m (make-array (list 2 3) :initial-element 0)))
  (setf (row-major-aref m 4) 9)
  (aref m 1 1)) ; => 9
```
