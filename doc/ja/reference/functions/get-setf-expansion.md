# get-setf-expansion

`(get-setf-expansion place &optional environment)`

`place` の setf 展開の 5 値を返します: 一時変数のリスト、それらが束縛する値フォームのリスト、ストア変数 1 つを持つリスト、書き込みフォーム、読み取りフォームです。lite 実装: 変数プレースは一時変数なしの `setq` ライタに展開され、アクセサ形式 `(f args...)` は引数ごとに一時変数を束縛して `setf` 経由で書き込みます。`environment` 引数は受け付けられますが無視されます(マクロの `&environment` パラメータは nil に束縛されます)。値はポータブルな `incf` 系マクロのイディオムのように `multiple-value-bind` で受け取ります。

```lisp
(multiple-value-bind (vars vals stores writer reader)
    (get-setf-expansion 'x)
  (list vars vals (length stores) reader)) ; => (NIL NIL 1 X)
```
