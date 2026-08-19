# torch:zero-grad

`(torch:zero-grad tensor-or-module)`

蓄積された勾配をクリアして (`nil` に戻して) 引数自身を返します。テンソルならそれ自身の勾配、**モジュール**なら [`torch:parameters`](torch-parameters.md) が到達するすべてのパラメータの勾配です。[`torch:backward`](torch-backward.md) は蓄積 (`+=`) するため、学習ループではステップ間でモデルに対してこれを呼びます。

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(torch:backward (torch:sum (torch:mul *w* *w*)))
(torch:grad *w*)              ; => #d(2.0 4.0)
(torch:grad (torch:zero-grad *w*)) ; => NIL

(defparameter *lin* (torch:linear 2 2))
(torch:backward (torch:sum (torch:forward *lin* (torch:tensor '((1.0 2.0))))))
(torch:zero-grad *lin*)
(torch:grad (torch:field *lin* :bias)) ; => NIL
```
