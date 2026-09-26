# member-if

`(member-if predicate list &key key)`

`predicate` を満たす最初の要素を `list` から探し、その要素から始まる部分リスト（末尾）を返します。満たす要素がなければ `nil` を返します。`:key` は述語に渡す前の各要素に適用されるセレクタです。返される末尾は元のリストと構造を共有します。リストでない `list` や、探索が末尾まで達したドットリストは `type-error` を通知します。述語ではなく要素の値で検索したい場合は `member` を、述語が偽を返す最初の要素で止めたい場合は [`member-if-not`](member-if-not.md) を使います。

```lisp
(member-if #'oddp '(2 4 5 6)) ; => (5 6)
```

```lisp
(member-if #'oddp '(1 2 3) :key #'1+) ; => (2 3)
```
