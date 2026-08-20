# torch:tanh

`(torch:tanh a)`

Differentiable elementwise hyperbolic tangent (`linalg:tanh`) -- the classic activation; the gradient is `g * (1 - tanh^2 x)`, computed from the forward result.

```lisp
(torch:data (torch:tanh (torch:tensor '(0.0)))) ; => #f(0.0)
```
