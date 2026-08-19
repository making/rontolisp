# torch:relu

`(torch:relu a)`

Differentiable elementwise `max(x, 0.0)` (`linalg:relu`); the gradient passes where `x > 0` and is `0` elsewhere (`0` at exactly `x = 0`, like PyTorch).

```lisp
(torch:data (torch:relu (torch:tensor '(-1.0 0.0 2.0)))) ; => #d(0.0 0.0 2.0)
```
