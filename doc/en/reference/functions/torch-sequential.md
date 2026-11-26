# torch:sequential

`(torch:sequential &rest layers)`

Returns a chain of layers (PyTorch's `nn.Sequential`): the forward threads its argument through each element in order. An element may be a module **or a plain function**, so an activation goes in as `(function torch:relu)` -- there is no separate activation-module type. The elements live in the single field `:layers`, and [`torch:parameters`](torch-parameters.md) walks that list, so every nested parameter is reachable.

A list of modules is itself a valid field value everywhere in this package, so a stack of N identical blocks needs no `ModuleList` type: hold the list in a field of your own [`torch:module`](torch-module.md) and the walk finds it.

```lisp
(defparameter *net*
  (torch:sequential (torch:linear 4 8) (function torch:relu) (torch:linear 8 2)))
(torch:shape (torch:forward *net* (torch:tensor (linalg:zeros '(3 4))))) ; => (3 2)
(length (torch:parameters *net*))                                       ; => 4
```
