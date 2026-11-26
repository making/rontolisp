# linalg:dot

`(linalg:dot a b)`

The numpy-style dot product, dispatching on the operand ranks: vector . vector gives a scalar (the inner product), matrix . vector and vector . matrix give a vector, and matrix . matrix gives the matrix product. A scalar operand multiplies elementwise, like [`linalg:mul`](linalg-mul.md). Mismatched inner dimensions signal an error. Both operands must be of rank <= 2: numpy's `np.dot` contracts a rank-n operand against the *second-to-last* axis of the other, which is not what a stacked matrix product means, so that shape signals an error pointing at [`linalg:matmul`](linalg-matmul.md) instead of returning a silently wrong answer. When only the matrix product is intended, `linalg:matmul` additionally rejects scalar operands and stacks rank >= 3.

```lisp
(linalg:dot #(1 2 3) #(4 5 6))       ; => 32
(linalg:dot #2A((1 2) (3 4)) #(1 1)) ; => #d(3.0 7.0)
(linalg:dot #(1 1) #2A((1 2) (3 4))) ; => #d(4.0 6.0)
```
