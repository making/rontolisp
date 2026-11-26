# torch:matmul

`(torch:matmul a b)`

Differentiable matrix product with `torch.matmul`'s rank rules: two vectors give the dot product (a scalar tensor), a matrix and a vector the usual products, and rank >= 3 on either side the batched product (`linalg:matmul`: the last two axes are the matrix, leading axes broadcast). Gradients flow to both operands -- `g . b^T` and `a^T . g` in the matrix case -- with batch axes summed back like every broadcasting adjoint. An operand that is a `torch:transpose` view of the last two axes is read in place -- `(torch:matmul q (torch:transpose k '(0 2 1)))` copies nothing, forward or backward.

```lisp
(torch:data (torch:matmul (torch:tensor '((1.0 2.0) (3.0 4.0)))
                          (torch:tensor '((5.0 6.0) (7.0 8.0)))))
; => #f((19.0 22.0) (43.0 50.0))
(torch:item (torch:matmul (torch:tensor '(1.0 2.0)) (torch:tensor '(3.0 4.0)))) ; => 11.0
```
