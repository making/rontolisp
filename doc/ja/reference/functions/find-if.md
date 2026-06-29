# find-if

`(find-if predicate list)`

`predicate` を満たす `list` の最初の要素を返します。満たすものがなければ `nil` を返します。インデックスや末尾ではなく要素そのものを返します。補集合の検索には `find-if-not` を使います。

```lisp
(find-if #'evenp '(1 3 6 7)) ; => 6
```
