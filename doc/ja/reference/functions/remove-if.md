# remove-if

`(remove-if predicate list)`

`predicate` を満たさ**ない** `list` の要素を含む新しいリストを返します (満たす要素は取り除かれます)。元のリストは変更されません。破壊的なバージョンには `delete-if` を使用してください。

```lisp
(remove-if #'evenp '(1 2 3 4)) ; => (1 3)
```
