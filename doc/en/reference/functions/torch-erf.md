# torch:erf

`(torch:erf a)`

Differentiable elementwise Gauss error function (PyTorch's `torch.erf`, over
[`linalg:erf`](linalg-erf.md)). The adjoint is the Gaussian
`2 / sqrt(pi) * e^(-x^2)`, so the gradient is exact rather than an
approximation of the forward approximation.

```lisp
(defparameter *x* (torch:tensor '(0.0) :requires-grad t))
(torch:backward (torch:sum (torch:erf *x*)))
(< (abs (- (torch:item (torch:tensor (torch:grad *x*))) 1.1283791670955126))
   1.0e-12)                              ; => T
```
