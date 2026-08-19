# torch:eval

`(torch:eval module)`

モジュールとすべてのサブモジュールを**評価**モード (PyTorch の `nn.Module.eval`) にし、モジュール自身を返します。[`torch:dropout`](torch-dropout.md) は恒等写像になります。推論ではさらに [`torch:no-grad`](../macros/torch-no-grad.md) も併用しますが、これは独立した別のスイッチです。

```lisp
(defparameter *drop* (torch:dropout 0.5))
(torch:training-p (torch:eval *drop*))                          ; => NIL
(torch:data (torch:forward *drop* (torch:tensor '(1.0 2.0))))   ; => #d(1.0 2.0)
```
