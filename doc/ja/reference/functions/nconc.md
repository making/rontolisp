# nconc

`(nconc list1 list2)`

`list1` の最後の cdr を `list2` を指すように設定することで2つのリストを破壊的に連結し、連結後のリストを返します。新しいコンスセルは割り当てられないため、`list1` はその場で変更されます。完全な Common Lisp とは異なり、rontolisp の `nconc` はちょうど2つのリストを取ります。`list1` が `nil` の場合、結果は単に `list2` になります。

```lisp
(nconc (list 1 2) (list 3 4)) ; => (1 2 3 4)
```
