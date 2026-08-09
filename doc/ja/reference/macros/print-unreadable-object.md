# print-unreadable-object

`(print-unreadable-object (object stream &key type identity) body...)`

本体が `stream` に出力した内容を `#<`...`>` で囲んで書き出し、`nil` を返します。`:type` が真なら先頭に [`type-of`](../functions/type-of.md) の指定子を出力し、本体が続く場合はその後にスペースを 1 つ入れます。`:identity` は受理されますがアドレスは出力しません — 値モデルにオブジェクト同一性のトークンがなく、バックエンドごとに用意すると同じプログラムの出力がバックエンドごとに変わってしまうためです。[`print-object`](../functions/print-object.md) メソッドの本体として使うのが通例です。

型指定子は通常のシンボルと同じように書き出されるため、パッケージ修飾子が付くかどうかは `*print-escape*` が決めます。他パッケージで定義された型は `prin1`/`~S` では `PKG:NAME`、`princ`/`~A` では `NAME` と出力されます。修飾子が不要な型では両者は一致します。

```lisp
(with-output-to-string (s)
  (print-unreadable-object ('x s :type nil)
    (princ "thing" s))) ; => "#<thing>"
```
