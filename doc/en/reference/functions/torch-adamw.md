# torch:adamw

`(torch:adamw params &key lr betas eps weight-decay)`

Returns an AdamW optimizer (PyTorch's `torch.optim.AdamW`) over `params`, a
module or a list of parameter tensors. [`torch:adam`](torch-adam.md)'s rule with
DECOUPLED weight decay: the parameter shrinks on its own before the Adam step,
instead of the decay entering the gradient and being rescaled by the adaptive
denominator. Per element, [`torch:step`](torch-step.md) computes

```text
param <- param - lr * weight-decay * param
m <- beta1 * m + (1 - beta1) * grad
v <- beta2 * v + (1 - beta2) * grad^2
param <- param - lr * (m / (1 - beta1^t)) / (sqrt(v / (1 - beta2^t)) + eps)
```

`:lr` defaults to `0.001`, `:betas` to `(0.9 0.999)`, `:eps` to `1.0e-8` and
`:weight-decay` to `0.01` -- PyTorch's default, against `torch:adam`'s `0`.

This is the rule a transformer is trained with, and a parameter that must NOT
decay (a bias, a LayerNorm gain, an embedding table) belongs in a SECOND
optimizer built with `:weight-decay 0.0`: two optimizers over disjoint parameter
lists are what `torch.optim`'s parameter GROUPS express here.

```lisp
(defparameter *w* (torch:parameter '(1.0)))
(defparameter *opt* (torch:adamw (list *w*) :lr 0.1 :weight-decay 0.5))
(torch:backward (torch:sum (torch:mul *w* *w*)))
(torch:step *opt*)
(< (abs (- (torch:item *w*) 0.85)) 1.0e-8) ; => T
```
