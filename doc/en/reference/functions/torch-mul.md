# torch:mul

`(torch:mul a b)`

Differentiable elementwise (Hadamard) `a * b` with numpy-style broadcasting (`linalg:mul`); the matrix product is [`torch:matmul`](torch-matmul.md). Each operand's gradient is the incoming gradient times the other operand.

```lisp
(torch:data (torch:mul (torch:tensor '(1.0 2.0 3.0)) (torch:tensor '(4.0 5.0 6.0)))) ; => #d(4.0 10.0 18.0)
(torch:data (torch:mul (torch:tensor '(1.0 2.0)) 2))                                  ; => #d(2.0 4.0)
```
