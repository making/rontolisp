# torch:adam

`(torch:adam params &key lr betas eps)`

Returns an Adam optimizer (PyTorch's `torch.optim.Adam`) over `params`, a module or a list of parameter tensors. `:lr` defaults to `0.001`, `:betas` to `(0.9 0.999)` (PyTorch's `(beta1, beta2)` tuple, as a two-element list) and `:eps` to `1.0e-8`. Per element, [`torch:step`](torch-step.md) computes

```text
m <- beta1 * m + (1 - beta1) * grad
v <- beta2 * v + (1 - beta2) * grad^2
param <- param - lr * (m / (1 - beta1^t)) / (sqrt(v / (1 - beta2^t)) + eps)
```

`t` is the optimizer's own [`torch:step-count`](torch-step-count.md), which is `1` during the first step -- so the first update is fully bias-corrected and has magnitude `lr`, not `lr * (1 - beta1)`.

```lisp
(defparameter *w* (torch:parameter '(1.0)))
(defparameter *opt* (torch:adam (list *w*) :lr 0.125))
(torch:backward (torch:sum (torch:mul *w* *w*)))
(torch:step *opt*)
(torch:step-count *opt*)                    ; => 1
(< (abs (- (torch:item *w*) 0.875)) 1.0e-8) ; => T
```
