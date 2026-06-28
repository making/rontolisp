# make-array

`(make-array dimensions &key initial-element)`

Creates and returns a new array. `dimensions` is an integer for a rank-1 vector, or a list of one or two integers for a rank-1 or rank-2 array (only ranks 1 and 2 are supported). `:initial-element` sets every cell to the given value, defaulting to nil. Elements are stored row-major with O(1) access via `aref`, and arrays are compared by identity (`eq`), so two distinct arrays are never `equal`. `make-array` and `aref` are not first-class function values -- `#'make-array` is unavailable, so call it directly.

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (aref a 0)) ; => 0
```
