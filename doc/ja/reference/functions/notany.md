# notany

`(notany predicate sequence)`

`sequence` のすべての要素で `predicate` が nil なら `t` を返し、満たす要素が 1 つでもあれば `nil` を返します。`some` の補集合版です。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。空のシーケンスでは `t` を返します。単一シーケンス形式のみです。

```lisp
(notany #'evenp '(1 3 5)) ; => t
```

```lisp
(notany #'digit-char-p "abc") ; => t
```
