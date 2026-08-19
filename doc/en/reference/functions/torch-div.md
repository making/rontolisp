# torch:div

`(torch:div a b)`

Differentiable elementwise `a / b` with numpy-style broadcasting (`linalg:div`): the numerator's gradient is `g / b`, the denominator's `-g * a / b^2`.

```lisp
(torch:data (torch:div (torch:tensor '(6.0 9.0)) (torch:tensor '(2.0 3.0)))) ; => #d(3.0 3.0)
```
