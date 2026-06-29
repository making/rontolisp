# elt

`(elt list index)`

`list` の 0 始まりの `index` にある要素を返します。Common Lisp の汎用シーケンス `elt` と異なり、rontolisp 版はリストにのみ対応し、文字列やベクタへのインデックス参照はできません（文字列には `char`、ベクタには `aref` を使ってください）。走査は引数を入れ替えた `nth` と同じです。

```lisp
(elt '(a b c) 1) ; => b
```
