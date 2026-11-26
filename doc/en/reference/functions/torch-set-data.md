# torch:set-data

`(torch:set-data tensor value)`

Replaces the tensor's data **in place** with value (a linalg array or a number) and returns the tensor. This is the parameter update of a training loop: it writes into the very tensor a module's fields already point at, so the layer keeps using it. The tape is untouched, so call it inside [`torch:no-grad`](../macros/torch-no-grad.md), like `torch.no_grad()` around an optimizer step.

```lisp
(defparameter *p* (torch:parameter '(1.0 2.0)))
(torch:no-grad
  (torch:set-data *p* (linalg:mul 2.0 (torch:data *p*))))
(torch:data *p*)             ; => #f(2.0 4.0)
(torch:requires-grad-p *p*)  ; => T
```
