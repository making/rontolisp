# linalg:inv

`(linalg:inv matrix)`

Returns the inverse of a square matrix, computed by Gauss-Jordan elimination on the augmented matrix `[a | I]`. Integer and rational inputs give an exact rational result. To solve a linear system it is usually clearer to call [`linalg:solve`](linalg-solve.md) directly.

```lisp
(linalg:inv #2A((1 2) (3 4))) ; => #2A((-2 1) (3/2 -1/2))
```

A singular matrix (one whose [`linalg:det`](linalg-det.md) is 0) has no inverse and signals an error:

```console
> (linalg:inv #2A((1 2) (2 4)))
Error: linalg: inv of a singular matrix
```
