# linalg:dot

`(linalg:dot a b)`

The numpy-style dot product, dispatching on the operand ranks: vector . vector gives a scalar (the inner product), matrix . vector and vector . matrix give a vector, and matrix . matrix gives the matrix product. A scalar operand multiplies elementwise, like [`linalg:mul`](linalg-mul.md). Mismatched inner dimensions signal an error. When only the matrix product is intended, [`linalg:matmul`](linalg-matmul.md) additionally rejects scalar operands.

```lisp
(linalg:dot (linalg:from-list '(1 2 3)) (linalg:from-list '(4 5 6)))     ; => 32
(linalg:dot (linalg:from-list '((1 2) (3 4))) (linalg:from-list '(1 1))) ; => #(3 7)
(linalg:dot (linalg:from-list '(1 1)) (linalg:from-list '((1 2) (3 4)))) ; => #(4 6)
```
