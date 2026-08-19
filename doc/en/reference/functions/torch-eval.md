# torch:eval

`(torch:eval module)`

Puts the module and every submodule into **evaluation** mode (PyTorch's `nn.Module.eval`) and returns the module: [`torch:dropout`](torch-dropout.md) becomes the identity. Inference additionally wants [`torch:no-grad`](../macros/torch-no-grad.md), which is a separate, orthogonal switch.

```lisp
(defparameter *drop* (torch:dropout 0.5))
(torch:training-p (torch:eval *drop*))                          ; => NIL
(torch:data (torch:forward *drop* (torch:tensor '(1.0 2.0))))   ; => #d(1.0 2.0)
```
