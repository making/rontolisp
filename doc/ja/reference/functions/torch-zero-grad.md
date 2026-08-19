# torch:zero-grad

`(torch:zero-grad tensor)`

テンソルに蓄積された勾配をクリアして (`nil` に戻して) テンソル自身を返します。[`torch:backward`](torch-backward.md) は蓄積 (`+=`) するため、学習ループではステップ間でパラメータの勾配をクリアします。

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(torch:backward (torch:sum (torch:mul *w* *w*)))
(torch:grad *w*)              ; => #d(2.0 4.0)
(torch:grad (torch:zero-grad *w*)) ; => NIL
```
