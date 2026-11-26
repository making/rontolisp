# torch:optimizer-params

`(torch:optimizer-params optimizer)`

オプティマイザが更新するパラメータテンソルのリスト、すなわちステップ関数がたどる対象を返します。モジュールから作った場合は、構築時に一度だけ実行した [`torch:parameters`](torch-parameters.md) の結果です。

```lisp
(defparameter *net* (torch:linear 2 3))
(defparameter *opt* (torch:sgd *net* :lr 0.1))
(length (torch:optimizer-params *opt*))            ; => 2
(torch:shape (car (torch:optimizer-params *opt*))) ; => (2 3)
```
