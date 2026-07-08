# linalg:inv

`(linalg:inv matrix)`

Returns the inverse of a square matrix, computed by Gauss-Jordan elimination on the augmented matrix `[a | I]`. The result is a packed double-float array -- linalg computes in floating point (speed over exactness), so a general inverse carries the usual rounding. To solve a linear system it is usually clearer to call [`linalg:solve`](linalg-solve.md) directly.

```lisp
(linalg:inv #2A((4 0) (2 4))) ; => #f((0.25 0.0) (-0.125 0.25))
```

A singular matrix (one whose [`linalg:det`](linalg-det.md) is 0) has no inverse and signals an error:

```console
> (linalg:inv #2A((1 2) (2 4)))
Error: linalg: inv of a singular matrix
```
