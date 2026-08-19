# torch:log

`(torch:log a)`

Differentiable elementwise natural logarithm (`linalg:log`); the gradient is `g / x`.

```lisp
(torch:data (torch:log (torch:tensor '(1.0 2.718281828459045)))) ; => #d(0.0 1.0)
```
