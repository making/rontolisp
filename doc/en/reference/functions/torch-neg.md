# torch:neg

`(torch:neg a)`

Differentiable elementwise negation (`linalg:negative`); the gradient is negated on the way back.

```lisp
(torch:data (torch:neg (torch:tensor '(1.0 -2.0)))) ; => #f(-1.0 2.0)
```
