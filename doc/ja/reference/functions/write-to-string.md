# write-to-string

`(write-to-string object &key escape readably pretty circle right-margin miser-width lines pprint-dispatch length level base radix case gensym array)`

`object` の印字表現を文字列として返します。キーワードなしでは読み戻し可能な（`prin1`）形式で、[prin1-to-string](prin1-to-string.md) の別名です。各キーワードは [write](write.md) と同じく、その 1 回の出力のあいだだけ対応する印字制御変数を束縛します（`:escape nil` は `princ` 形式です）。コンパイルされたバックエンドでは、キーワードが届くのは直接呼び出しだけです。値としての `#'write-to-string` は 1 引数の関数です。

```lisp
(write-to-string '(a b 3)) ; => "(A B 3)"
```

```lisp
(write-to-string '(a b c d) :length 2 :case :downcase) ; => "(a b ...)"
```
