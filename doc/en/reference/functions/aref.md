# aref

`(aref array &rest subscripts)`

Returns the element of `array` at the given 0-based subscripts, one per dimension (none for a rank-0 array, one for a rank-1 vector, two for a rank-2 array, and so on). A string is a rank-1 character array, so `(aref s i)` reads like [`char`](char.md) (writing a string element goes through the `schar`/`char` setf place instead). Flat rank-independent access is available via [`row-major-aref`](row-major-aref.md). To modify an element, use `aref` as a `setf` place: `(setf (aref array i j) value)`, which also works with `incf`/`decf`/`push`. `#'aref` is a first-class function value, so it can be passed to `mapcar`/`funcall` like any other function.

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (setf (aref a 1) 9)
  (aref a 1)) ; => 9
(aref (make-array nil :initial-element 5)) ; => 5
```
