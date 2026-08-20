# torch:exp

`(torch:exp a)`

Differentiable elementwise `e^x` (`linalg:exp`); the backward pass reuses the forward result (`d/dx e^x = e^x`).

The result below is rounded on purpose: `e^x` is whatever the platform's own `exp` returns, and its last digit may differ between machines and backends.

```lisp
(linalg:emap (lambda (x) (/ (round (* x 1000)) 1000.0)) (torch:data (torch:exp (torch:tensor '(0.0 1.0))))) ; => #d(1.0 2.718)
```
