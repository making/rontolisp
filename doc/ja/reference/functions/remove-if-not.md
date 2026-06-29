# remove-if-not

`(remove-if-not predicate list)`

`predicate` を満たす `list` の要素だけを残した新しいリストを返します (満たさない要素は取り除かれます)。これは `remove-if` の補集合に相当します。元のリストは変更されません。

```lisp
(remove-if-not #'evenp '(1 2 3 4)) ; => (2 4)
```
