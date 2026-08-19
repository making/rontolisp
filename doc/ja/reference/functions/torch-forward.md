# torch:forward

`(torch:forward module &rest args)`

モジュールの順伝播 -- `(funcall モジュールの forward-fn module args...)` -- を実行し、出力テンソルを返します。素の**関数**も受け付けて単に適用するため、状態を持たないステップ (活性化関数や reshape) はラッパーレイヤーなしで [`torch:sequential`](torch-sequential.md) に置けます。このパッケージに活性化関数のモジュール型がないのはそのためです。

```lisp
(defparameter *lin* (torch:linear 2 2))
(torch:set-field *lin* :weight (torch:parameter '((1.0 0.0) (0.0 -1.0))))
(torch:set-field *lin* :bias (torch:parameter '(0.0 0.0)))
(torch:data (torch:forward *lin* (torch:tensor '((2.0 3.0)))))       ; => #d((2.0 -3.0))
(torch:data (torch:forward (function torch:relu) (torch:tensor '(-1.0 2.0)))) ; => #d(0.0 2.0)
```
