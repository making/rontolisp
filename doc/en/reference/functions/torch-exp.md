# torch:exp

`(torch:exp a)`

Differentiable elementwise `e^x` (`linalg:exp`); the backward pass reuses the forward result (`d/dx e^x = e^x`).

The result below is rounded, from the days when `e^x` was the platform's own `exp`; it is fdlibm's on every backend and machine now, so the full digits would agree everywhere too.

```lisp
(linalg:emap (lambda (x) (/ (round (* x 1000)) 1000.0)) (torch:data (torch:exp (torch:tensor '(0.0 1.0))))) ; => #f(1.0 2.718)
```
