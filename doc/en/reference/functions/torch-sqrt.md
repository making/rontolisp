# torch:sqrt

`(torch:sqrt a)`

Differentiable elementwise square root (`linalg:sqrt`); the gradient is `g / (2 sqrt x)`.

```lisp
(torch:data (torch:sqrt (torch:tensor '(4.0 9.0)))) ; => #d(2.0 3.0)
```
