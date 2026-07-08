# linalg:eye

`(linalg:eye n)`

Creates the `n`-by-`n` identity matrix: ones on the main diagonal, zeros everywhere else. Multiplying by it with [`linalg:matmul`](linalg-matmul.md) leaves a matrix unchanged, and it is a convenient reference operand for [`linalg:array-equal`](linalg-array-equal.md).

```lisp
(linalg:eye 3) ; => #2A((1.0 0.0 0.0) (0.0 1.0 0.0) (0.0 0.0 1.0))
```
