# find-if-not

`(find-if-not predicate list)`

`predicate` を満たさ**ない** `list` の最初の要素を返します。すべての要素が満たす場合は `nil` を返します。要素そのものを返します。これは `find-if` の補集合版です。

```lisp
(find-if-not #'evenp '(2 4 5 6)) ; => 5
```
