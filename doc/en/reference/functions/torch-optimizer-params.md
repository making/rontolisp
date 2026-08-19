# torch:optimizer-params

`(torch:optimizer-params optimizer)`

Returns the list of parameter tensors the optimizer updates -- what its step function walks. When the optimizer was built over a module, this is the [`torch:parameters`](torch-parameters.md) walk taken once, at construction.

```lisp
(defparameter *net* (torch:linear 2 3))
(defparameter *opt* (torch:sgd *net* :lr 0.1))
(length (torch:optimizer-params *opt*))            ; => 2
(torch:shape (car (torch:optimizer-params *opt*))) ; => (2 3)
```
