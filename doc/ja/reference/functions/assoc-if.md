# assoc-if

`(assoc-if predicate alist &key key)`

連想リストを検索し、car が `predicate` を満たす最初のペアを返します。該当するものがなければ `nil` を返します。`:key` は述語に渡す前の car に適用されるセレクタです。リストでない `alist` や、探索が末尾まで達したドットリストは `type-error` を通知します。これは `assoc` の述語ベース版です。述語が偽を返す最初の car で止めたい場合は [`assoc-if-not`](assoc-if-not.md) を使います。

```lisp
(assoc-if #'oddp '((2 a) (3 b))) ; => (3 B)
```

```lisp
(assoc-if #'oddp '((1 . a) (2 . b)) :key #'1+) ; => (2 . B)
```
