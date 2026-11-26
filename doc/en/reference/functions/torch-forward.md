# torch:forward

`(torch:forward module &rest args)`

Runs a module's forward pass -- `(funcall its forward-fn module args...)` -- and returns the output tensor. A plain **function** is also accepted and simply applied, so a stateless step (an activation, a reshape) can sit in a [`torch:sequential`](torch-sequential.md) without a wrapper layer existing for it; that is why the package has no activation-module type.

```lisp
(defparameter *lin* (torch:linear 2 2))
(torch:set-field *lin* :weight (torch:parameter '((1.0 0.0) (0.0 -1.0))))
(torch:set-field *lin* :bias (torch:parameter '(0.0 0.0)))
(torch:data (torch:forward *lin* (torch:tensor '((2.0 3.0)))))       ; => #f((2.0 -3.0))
(torch:data (torch:forward (function torch:relu) (torch:tensor '(-1.0 2.0)))) ; => #f(0.0 2.0)
```
