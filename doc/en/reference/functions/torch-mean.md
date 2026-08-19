# torch:mean

`(torch:mean a &key axis keepdims)`

Differentiable arithmetic mean (`linalg:mean`, same `:axis` / `:keepdims` rules as [`torch:sum`](torch-sum.md)); the backward pass is the sum adjoint divided by the reduced element count. The mean of a squared [`torch:sub`](torch-sub.md) is the MSE loss.

```lisp
(torch:item (torch:mean (torch:tensor '(1.0 2.0 3.0)))) ; => 2.0
```
