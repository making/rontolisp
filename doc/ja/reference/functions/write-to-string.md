# write-to-string

`(write-to-string object)`

`object` の読み戻し可能な（`prin1`）印字表現を文字列として返します。[prin1-to-string](prin1-to-string.md) の別名です。Common Lisp の `write` の完全なキーワード引数（`:escape`、`:base` など）はサポートされていません。

```lisp
(write-to-string '(a b 3)) ; => "(a b 3)"
```
