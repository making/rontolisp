# linalg:div

`(linalg:div a b)`

Divides `a` by `b` elementwise, returning a fresh packed double-float array. Either operand may be a scalar, which is broadcast over the other operand's shape; two array operands must have equal shapes (a mismatch signals an error).

```lisp
(linalg:div #(1 2 3) 2) ; => #d(0.5 1.0 1.5)
```
