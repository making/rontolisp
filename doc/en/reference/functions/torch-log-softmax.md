# torch:log-softmax

`(torch:log-softmax a &key axis)`

Differentiable log-softmax (`linalg:log-softmax`, the numerically stable half of a cross-entropy loss): computed as `(x - max) - log(sum(exp(x - max)))`, never as `log` of [`torch:softmax`](torch-softmax.md). The backward pass is `g - softmax(x) * sum(g)`.

```lisp
(torch:data (torch:log-softmax (torch:tensor '(0.0 0.0))))
; => #d(-0.6931471805599453 -0.6931471805599453)
```
