# linalg:mul

`(linalg:mul a b)`

Multiplies `a` and `b` elementwise (the Hadamard product), returning a fresh array -- this is NOT the matrix product; for that use [`linalg:matmul`](linalg-matmul.md) or [`linalg:dot`](linalg-dot.md). Either operand may be a scalar, which is broadcast over the other operand's shape; two array operands must have equal shapes (a mismatch signals an error).

```lisp
(linalg:mul 2 #2A((1 2) (3 4))) ; => #f((2.0 4.0) (6.0 8.0))
(linalg:mul #2A((1 2) (3 4))
            #2A((5 6) (7 8)))   ; => #f((5.0 12.0) (21.0 32.0))
```
