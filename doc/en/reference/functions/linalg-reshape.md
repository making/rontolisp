# linalg:reshape

`(linalg:reshape array shape)`

Returns a fresh array with the given shape and the same elements in row-major order. `shape` is an integer for a vector or a list `(rows cols)` for a matrix, and its total size must match the input's [`linalg:size`](linalg-size.md) -- a mismatch signals an error. One extent may be `-1` and is inferred from the element count (numpy's rule); a bare `-1` shape flattens, and more than one `-1` signals an error. [`linalg:flatten`](linalg-flatten.md) is the common special case of reshaping to a vector.

```lisp
(linalg:reshape (linalg:arange 6) '(2 3)) ; => #d((0.0 1.0 2.0) (3.0 4.0 5.0))
(linalg:shape (linalg:reshape (linalg:arange 12) '(3 -1))) ; => (3 4)
```
