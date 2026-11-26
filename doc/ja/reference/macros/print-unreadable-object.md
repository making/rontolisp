# print-unreadable-object

`(print-unreadable-object (object stream &key type identity) body...)`

本体が `stream` に出力した内容を `#<`...`>` で囲んで書き出し、`nil` を返します。`:type` が真なら先頭に [`type-of`](../functions/type-of.md) の指定子を出力し、本体が続く場合はその後にスペースを 1 つ入れます。`:identity` は受理されますがアドレスは出力しません — 値モデルにオブジェクト同一性のトークンがなく、バックエンドごとに用意すると同じプログラムの出力がバックエンドごとに変わってしまうためです。[`print-object`](../functions/print-object.md) メソッドの本体として使うのが通例です。`*print-readably*` の下では本体を実行する前に、`object` について [`print-not-readable`](../functions/print-not-readable-object.md) をシグナルします。

型指定子は通常のシンボルと同じように書き出されます。`prin1`/`~S` では現在のパッケージが必要とする修飾子が付きます。名前がアクセス可能なら付かず、そうでなければ `PKG:NAME` か `PKG::NAME`、他パッケージから印字する `cl-user` の型は `COMMON-LISP-USER::NAME` です。`princ`/`~A` では `NAME` だけです。どちらも `*print-case*` に従います。

```lisp
(with-output-to-string (s)
  (print-unreadable-object ('x s :type nil)
    (princ "thing" s))) ; => "#<thing>"
```
