# find-if

`(find-if predicate sequence)`

`sequence` の要素のうち `predicate` を満たす最初の要素を返します。満たす要素がなければ `nil` を返します。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。インデックスや末尾ではなく要素そのものを返します。補集合の探索には `find-if-not` を使います。

```lisp
(find-if #'evenp '(1 3 6 7)) ; => 6
```

```lisp
(find-if #'digit-char-p "ab3c") ; => #\3
```
