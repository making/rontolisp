# constantp

`(constantp form &optional environment)`

`form` が定数オブジェクトであれば `t` を、そうでなければ `nil` を返します。これは軽量実装で、自己評価オブジェクト（数値、文字列、文字、キーワード、`t`、`nil`）と `(quote x)` 形式を認識します。それ以外（プレーンなシンボルや関数呼び出し形式を含む）は `nil` になります。偽陰性は無害です（利用側が処理を実行時に先送りするだけです）。省略可能な `environment` 引数は受け付けられますが無視されます（マクロの `&environment` パラメータは nil に束縛されます）。`--no-gc` を除くすべてのバックエンドで利用できます。

```lisp
(list (constantp 5) (constantp 'x) (constantp '(quote y))) ; => (T NIL T)
```
