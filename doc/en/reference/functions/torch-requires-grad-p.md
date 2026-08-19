# torch:requires-grad-p

`(torch:requires-grad-p tensor)`

Returns whether the tensor participates in autograd: a leaf created with `:requires-grad t`, or any result recorded on the tape (computed -- outside [`torch:no-grad`](../macros/torch-no-grad.md) -- from something that participates).

```lisp
(defparameter *w* (torch:tensor '(1.0) :requires-grad t))
(torch:requires-grad-p *w*)                  ; => T
(torch:requires-grad-p (torch:mul *w* 2.0))  ; => T
(torch:requires-grad-p (torch:tensor '(1.0))) ; => NIL
```
