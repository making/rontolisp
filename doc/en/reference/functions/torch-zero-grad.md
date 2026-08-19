# torch:zero-grad

`(torch:zero-grad tensor)`

Clears the tensor's accumulated gradient (back to `nil`) and returns the tensor. Because [`torch:backward`](torch-backward.md) accumulates (`+=`), a training loop clears its parameters' gradients between steps.

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(torch:backward (torch:sum (torch:mul *w* *w*)))
(torch:grad *w*)              ; => #d(2.0 4.0)
(torch:grad (torch:zero-grad *w*)) ; => NIL
```
