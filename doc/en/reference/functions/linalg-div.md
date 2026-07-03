# linalg:div

`(linalg:div a b)`

Divides `a` by `b` elementwise, returning a fresh array. Either operand may be a scalar, which is broadcast over the other operand's shape; two array operands must have equal shapes (a mismatch signals an error). Division of integers produces exact ratios, not floats -- the same exact arithmetic that makes [`linalg:inv`](linalg-inv.md) and [`linalg:solve`](linalg-solve.md) exact.

```lisp
(linalg:div #(1 2 3) 2) ; => #(1/2 1 3/2)
```
