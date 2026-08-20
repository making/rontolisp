# torch:sgd

`(torch:sgd params &key lr momentum weight-decay)`

Returns a stochastic gradient descent optimizer (PyTorch's `torch.optim.SGD`) over `params`, a module or a list of parameter tensors. `:lr` defaults to `0.01`, `:momentum` and `:weight-decay` to `0`. Per element, [`torch:step`](torch-step.md) computes

```text
g   <- grad + weight-decay * param
buf <- momentum * buf + g          ; only when momentum is non-zero
param <- param - lr * (momentum non-zero ? buf : g)
```

The momentum buffer starts at zero, which is PyTorch's clone-on-first-step. The hyper-parameters are ordinary fields, so a learning-rate schedule is `(torch:set-field opt :lr new)`.

```lisp
(defparameter *p* (torch:parameter '(1.0 2.0)))
(defparameter *opt* (torch:sgd (list *p*) :lr 0.125))
(torch:backward (torch:sum (torch:mul *p* *p*)))
(torch:step *opt*)
(torch:data *p*)        ; => #f(0.75 1.5)
(torch:field *opt* :lr) ; => 0.125
```
