# torch:zero-grad

`(torch:zero-grad tensor-or-module)`

Clears accumulated gradients (back to `nil`) and returns the argument: for a tensor its own gradient, for a **module** the gradient of every parameter [`torch:parameters`](torch-parameters.md) reaches. Because [`torch:backward`](torch-backward.md) accumulates (`+=`), a training loop calls this on its model between steps.

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(torch:backward (torch:sum (torch:mul *w* *w*)))
(torch:grad *w*)              ; => #f(2.0 4.0)
(torch:grad (torch:zero-grad *w*)) ; => NIL

(defparameter *lin* (torch:linear 2 2))
(torch:backward (torch:sum (torch:forward *lin* (torch:tensor '((1.0 2.0))))))
(torch:zero-grad *lin*)
(torch:grad (torch:field *lin* :bias)) ; => NIL
```
