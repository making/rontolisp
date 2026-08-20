# torch:set-data

`(torch:set-data tensor value)`

テンソルのデータを value (linalg 配列または数値) で**破壊的に**置き換え、テンソル自身を返します。学習ループのパラメータ更新はこれです。モジュールのフィールドが既に指しているテンソルそのものに書き込むため、レイヤーは同じテンソルを使い続けます。テープには触れないので、オプティマイザのステップを `torch.no_grad()` で囲むのと同様に [`torch:no-grad`](../macros/torch-no-grad.md) の中で呼びます。

```lisp
(defparameter *p* (torch:parameter '(1.0 2.0)))
(torch:no-grad
  (torch:set-data *p* (linalg:mul 2.0 (torch:data *p*))))
(torch:data *p*)             ; => #f(2.0 4.0)
(torch:requires-grad-p *p*)  ; => T
```
