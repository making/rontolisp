# torch:no-grad

`(torch:no-grad body...)`

Runs the body with gradient recording disabled: the `torch` operations inside compute their values as usual but record nothing on the autograd tape, so the results are constant leaves ([`torch:requires-grad-p`](../functions/torch-requires-grad-p.md) answers `nil`) and no history is retained. This is how a training loop's parameter update -- and inference in general -- stays off the tape; the per-tensor spelling is [`torch:detach`](../functions/torch-detach.md).

Mechanically it dynamically rebinds the internal `torch::*grad-enabled*` variable to `nil` around the body, so recording resumes when the form exits.

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(torch:no-grad
  (torch:requires-grad-p (torch:mul *w* 2.0))) ; => NIL
(torch:requires-grad-p (torch:mul *w* 2.0))    ; => T
```
