# torch:power

`(torch:power a b)`

Differentiable elementwise `a ** b` (`linalg:power`); either operand may be a scalar and both are differentiable. The base's gradient is `g * b * a^(b-1)`; the exponent's -- computed only when the exponent tracks gradients -- is `g * a^b * ln a`, which is only meaningful for a positive base.

```lisp
(torch:data (torch:power (torch:tensor '(2.0 3.0)) 2)) ; => #f(4.0 9.0)
```
