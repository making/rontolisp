# torch:exp

`(torch:exp a)`

Differentiable elementwise `e^x` (`linalg:exp`); the backward pass reuses the forward result (`d/dx e^x = e^x`).

```lisp
(torch:data (torch:exp (torch:tensor '(0.0 1.0)))) ; => #d(1.0 2.718281828459045)
```
