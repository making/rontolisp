# elt

`(elt sequence index)`

`sequence` の 0 始まりの `index` にある要素を返します。文字列の場合は文字を、リストの場合はその要素を返します（走査は引数を入れ替えた `nth` と同じです）。Common Lisp の汎用シーケンス `elt` と異なり、rontolisp 版はベクタへのインデックス参照はできません（ベクタには `aref` を使ってください）。

```lisp
(elt '(a b c) 1) ; => b
```

```lisp
(elt "abcd" 1) ; => #\b
```
