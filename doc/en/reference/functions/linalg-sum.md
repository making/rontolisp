# linalg:sum

`(linalg:sum array &optional axis keepdims)`

Returns the sum of every element of a vector or matrix. Like a reduction in numpy, the result follows the element type: a packed double-float array (anything built by a linalg constructor) gives a double, while a plain integer array gives an integer. For the average, use [`linalg:mean`](linalg-mean.md).

With an integer `axis` (negative counts from the end, numpy's rule) it instead sums along that axis: the axis is dropped from the result -- kept with extent 1 under a non-nil `keepdims` -- and a vector without `keepdims` reduces to the scalar itself. A non-nil `keepdims` with no axis wraps the full sum in an all-ones-shape array, as in numpy.

```lisp
(linalg:sum #2A((1 2) (3 4))) ; => 10
(linalg:sum #2A((1 2 3) (4 5 6)) 0) ; => #d(5.0 7.0 9.0)
(linalg:sum #2A((1 2 3) (4 5 6)) 1 t) ; => #d((6.0) (15.0))
```
