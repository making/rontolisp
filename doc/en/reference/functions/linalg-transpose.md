# linalg:transpose

`(linalg:transpose array)`

Returns the transpose of a matrix: element `(i j)` of the result is element `(j i)` of the input. Like numpy, a rank-1 vector is returned unchanged -- there is no distinct row/column vector representation. To turn a vector into a genuine 1-row or 1-column matrix, use [`linalg:reshape`](linalg-reshape.md).

```lisp
(linalg:transpose #2A((1 2 3) (4 5 6))) ; => #f((1.0 4.0) (2.0 5.0) (3.0 6.0))
(linalg:transpose #(1 2 3))             ; => #(1 2 3)
```
