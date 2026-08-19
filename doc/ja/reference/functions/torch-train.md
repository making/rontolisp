# torch:train

`(torch:train module &optional mode)`

モジュールとすべてのサブモジュールを**学習**モード (PyTorch の `nn.Module.train`) にし、モジュール自身を返します。mode に明示的に `nil` を渡すと [`torch:eval`](torch-eval.md) と同じです。現在このフラグを読むのは [`torch:dropout`](torch-dropout.md) だけです。

```lisp
(defparameter *net* (torch:sequential (torch:dropout 0.5)))
(torch:eval *net*)
(torch:training-p (torch:train *net*))                       ; => T
(torch:training-p (car (torch:field *net* :layers)))         ; => T
```
