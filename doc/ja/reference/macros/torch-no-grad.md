# torch:no-grad

`(torch:no-grad body...)`

勾配の記録を無効にして本体を実行します。内側の `torch` 演算は通常どおり値を計算しますが自動微分テープには何も記録しないため、結果は定数の葉になり ([`torch:requires-grad-p`](../functions/torch-requires-grad-p.md) は `nil`)、履歴も保持されません。学習ループのパラメータ更新や推論一般をテープの外に置く方法です。テンソル単位の綴りは [`torch:detach`](../functions/torch-detach.md) です。

仕組みとしては、内部変数 `torch::*grad-enabled*` を本体の間だけ動的に `nil` へ再束縛するので、フォームを抜けると記録は再開されます。

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(torch:no-grad
  (torch:requires-grad-p (torch:mul *w* 2.0))) ; => NIL
(torch:requires-grad-p (torch:mul *w* 2.0))    ; => T
```
