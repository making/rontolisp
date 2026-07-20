# notevery

`(notevery predicate sequence)`

`sequence` の少なくとも 1 つの要素で `predicate` が nil なら `t` を返し、すべての要素が満たす場合は `nil` を返します。`every` の補集合版です。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。空のシーケンスでは `nil` を返します。単一シーケンス形式のみです。

```lisp
(notevery #'evenp '(2 4 5)) ; => T
```

```lisp
(notevery #'digit-char-p "12a") ; => T
```
