# linalg:transpose

`(linalg:transpose array &optional axes)`

Returns the transpose of a matrix: element `(i j)` of the result is element `(j i)` of the input. Like numpy, a rank-1 vector is returned unchanged -- there is no distinct row/column vector representation. To turn a vector into a genuine 1-row or 1-column matrix, use [`linalg:reshape`](linalg-reshape.md).

With an `axes` list (numpy's `x.transpose(0, 3, 1, 2)`), returns the rank-n axis permutation instead: axis `k` of the result is axis `(nth k axes)` of the input, so the result's shape is the input's shape reindexed by `axes`. The list must name each of the input's axes exactly once.

```lisp
(linalg:transpose #2A((1 2 3) (4 5 6))) ; => #d((1.0 4.0) (2.0 5.0) (3.0 6.0))
(linalg:transpose #(1 2 3))             ; => #(1 2 3)
(linalg:shape (linalg:transpose (linalg:zeros '(2 3 4)) '(1 0 2))) ; => (3 2 4)
```
