# torch:grad

`(torch:grad tensor)`

Returns the gradient [`torch:backward`](torch-backward.md) accumulated into the tensor -- a raw linalg value of the data's shape -- or `nil` before any backward pass has reached it. Gradients accumulate (`+=`) across backward calls; [`torch:zero-grad`](torch-zero-grad.md) clears the slot.

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(torch:grad *w*)                                   ; => NIL
(torch:backward (torch:sum (torch:mul *w* *w*)))
(torch:grad *w*)                                   ; => #f(2.0 4.0)
```
