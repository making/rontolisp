# member-if

`(member-if predicate list)`

`predicate` を満たす最初の要素を `list` から探し、その要素から始まる部分リスト（末尾）を返します。満たす要素がなければ `nil` を返します。返される末尾は元のリストと構造を共有します。述語ではなく要素の値で検索したい場合は `member` を使います。

```lisp
(member-if #'oddp '(2 4 5 6)) ; => (5 6)
```
