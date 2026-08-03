# write

`(write object &key stream escape readably pretty circle right-margin miser-width lines pprint-dispatch)`

`object` を `stream`（既定は標準出力）に書き出し、`object` を返します。各キーワードは、その 1 回の出力のあいだだけ対応する印字制御変数を束縛します（Common Lisp の定義そのものです）。`:escape`（既定は `*print-escape*` の値、すなわち `t`）は読み戻せる `prin1` 形式を選び、`:escape nil` は `princ` 形式を選びます。残りのキーワードも受け付けて変数を束縛しますが、印字のレイアウトは変わりません。理由は `pprint` を参照してください。なお `write-to-string` はここではキーワードを取りません。必要なときは `(with-output-to-string (s) (write x :stream s ...))` を使ってください。

```lisp
(with-output-to-string (s) (write "hi" :stream s :escape nil)) ; => "hi"
```
