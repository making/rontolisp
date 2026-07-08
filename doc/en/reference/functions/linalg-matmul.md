# linalg:matmul

`(linalg:matmul a b)`

The matrix product of `a` and `b` (also matrix . vector). It behaves like [`linalg:dot`](linalg-dot.md) but signals an error when either operand is a scalar, catching the mistake of writing a matrix product where an elementwise [`linalg:mul`](linalg-mul.md) would silently apply. The inner dimensions must agree; a mismatch signals an error.

```lisp
(linalg:matmul #2A((1 2) (3 4))
               #2A((5 6) (7 8))) ; => #f((19.0 22.0) (43.0 50.0))
```
