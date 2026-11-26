# linalg:det

`(linalg:det matrix)`

Returns the determinant of a square matrix, computed by Gaussian elimination with partial pivoting in floating point. A non-square argument signals an error. A zero determinant is the condition under which [`linalg:inv`](linalg-inv.md) and [`linalg:solve`](linalg-solve.md) fail; note that because the computation is floating point, a nearly singular matrix may yield a small epsilon rather than exactly `0` (the example below is exact only because its elimination is roundoff-free).

```lisp
(linalg:det #2A((1 2) (3 4))) ; => -2.0
(linalg:det #2A((1 2) (2 4))) ; => 0.0
```
