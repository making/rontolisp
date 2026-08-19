# torch:detach

`(torch:detach tensor)`

Returns a new leaf tensor sharing the tensor's data but cut off from the autograd tape: no `requires-grad`, no recorded history, so nothing computed from it flows gradients back. The whole-block spelling is the [`torch:no-grad`](../macros/torch-no-grad.md) macro.

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(defparameter *y* (torch:mul *w* 3.0))
(torch:requires-grad-p *y*)                ; => T
(torch:requires-grad-p (torch:detach *y*)) ; => NIL
```
