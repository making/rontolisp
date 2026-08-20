# torch:clip-grad-norm

`(torch:clip-grad-norm params max-norm)`

Gradient-norm clipping (PyTorch's `torch.nn.utils.clip_grad_norm_`) over
`params`: a module, an optimizer's parameter list, or a plain list of tensors.

Returns the TOTAL L2 norm of every gradient, taken over all of them at once as
if they were one long vector -- the norm as MEASURED, before any clipping, so a
training loop can log it. When that norm exceeds `max-norm`, every gradient is
scaled IN PLACE by `max-norm / (norm + 1e-6)`, PyTorch's denominator; otherwise
nothing is touched. A parameter no gradient reached is skipped.

Call it between [`torch:backward`](torch-backward.md) and
[`torch:step`](torch-step.md): it rewrites the gradients the optimizer is about
to read, and touches no tape.

```lisp
(defparameter *w* (torch:parameter '(3.0 4.0)))
(torch:backward (torch:sum (torch:mul *w* *w*)))   ; grad = (6 8), norm 10
(< (abs (- (torch:clip-grad-norm (list *w*) 1.0) 10.0)) 1.0e-9)  ; => T
(< (abs (- (aref (torch:grad *w*) 0) 0.6)) 1.0e-6)               ; => T
```
