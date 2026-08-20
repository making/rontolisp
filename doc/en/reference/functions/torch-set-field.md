# torch:set-field

`(torch:set-field module name value)`

Sets the module's named field (adding it when it is new) and returns the module. Replacing a parameter this way re-binds a layer to a given set of weights -- which is what makes a layer's output reproducible in a test or an example.

```lisp
(defparameter *lin* (torch:linear 3 2))
(torch:set-field *lin* :weight (torch:parameter '((1.0 0.0) (0.0 1.0) (1.0 1.0))))
(torch:set-field *lin* :bias (torch:parameter '(0.5 -0.5)))
(torch:data (torch:forward *lin* (torch:tensor '((1.0 2.0 3.0))))) ; => #f((4.5 4.5))
```
