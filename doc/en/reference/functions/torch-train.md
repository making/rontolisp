# torch:train

`(torch:train module &optional mode)`

Puts the module and every submodule into **training** mode (PyTorch's `nn.Module.train`) and returns the module; an explicit `nil` mode is the same as [`torch:eval`](torch-eval.md). Only [`torch:dropout`](torch-dropout.md) reads the flag today.

```lisp
(defparameter *net* (torch:sequential (torch:dropout 0.5)))
(torch:eval *net*)
(torch:training-p (torch:train *net*))                       ; => T
(torch:training-p (car (torch:field *net* :layers)))         ; => T
```
