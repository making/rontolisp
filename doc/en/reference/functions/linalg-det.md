# linalg:det

`(linalg:det matrix)`

Returns the determinant of a square matrix, computed by Gaussian elimination with partial pivoting. Integer and rational inputs give an exact result -- a singular matrix yields exactly `0`, never a float epsilon. A non-square argument signals an error. A zero determinant is the condition under which [`linalg:inv`](linalg-inv.md) and [`linalg:solve`](linalg-solve.md) fail.

```lisp
(linalg:det #2A((1 2) (3 4))) ; => -2
(linalg:det #2A((1 2) (2 4))) ; => 0
```
