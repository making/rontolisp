# linalg:mul

`(linalg:mul a b)`

Multiplies `a` and `b` elementwise (the Hadamard product), returning a fresh array -- this is NOT the matrix product; for that use [`linalg:matmul`](linalg-matmul.md) or [`linalg:dot`](linalg-dot.md). The operands broadcast by numpy's rules, exactly as [`linalg:add`](linalg-add.md) describes: a scalar broadcasts over the other operand's shape, and two arrays of different shapes broadcast along their trailing axes when each aligned extent pair is equal or contains a 1.

```lisp
(linalg:mul 2 #2A((1 2) (3 4))) ; => #d((2.0 4.0) (6.0 8.0))
(linalg:mul #2A((1 2) (3 4))
            #2A((5 6) (7 8)))   ; => #d((5.0 12.0) (21.0 32.0))
(linalg:mul #2A((1 2) (3 4))
            #(10 20))           ; => #d((10.0 40.0) (30.0 80.0))
```
