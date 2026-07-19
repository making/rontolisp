# print-unreadable-object

`(print-unreadable-object (object stream &key type identity) body...)`

本体の出力を `#<`...`>` で囲んで `stream` に書き、`nil` を返します。`:type` が真なら先頭に `class-of` の指定子(+ 空白)を出力します。`:identity` は受け付けますが出力しません(表示可能なアドレスがありません)。ライブラリの `print-object` メソッドで使われます。

```lisp
(with-output-to-string (s)
  (print-unreadable-object ('x s :type nil)
    (princ "thing" s))) ; => "#<thing>"
```
