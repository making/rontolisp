# write

`(write object &key stream escape readably pretty circle right-margin miser-width lines pprint-dispatch length level base radix case gensym array)`

`object` を `stream`（既定は標準出力）に書き出し、`object` を返します。各キーワードは、その 1 回の出力のあいだだけ対応する印字制御変数を束縛します（Common Lisp の定義そのものです）。`:escape`（既定は `*print-escape*` の値、すなわち `t`）は読み戻せる `prin1` 形式を選び、`:escape nil` は `princ` 形式を選びます。`:length` と `:level` はリストとベクタを切り詰め（`(1 2 ...)`、`#`）、`:case` はシンボルの大小文字を変換し、`:gensym nil` は gensym の `#:` を落とし、`:base` / `:radix` は整数と有理数の綴りを変えます（`FF`、`#xFF`、`255.`）。`:pretty`、`:circle`、`:array` と 3 つの幅のキーワードは受け付けて変数を束縛しますが、印字のレイアウトは変わりません。理由は `pprint` を参照してください。`write-to-string` も同じキーワードを取ります。

```lisp
(with-output-to-string (s) (write "hi" :stream s :escape nil)) ; => "hi"
```

```lisp
(with-output-to-string (s) (write '(foo bar baz) :stream s :case :downcase :length 2)) ; => "(foo bar ...)"
```
