# notevery

`(notevery predicate list)`

`list` の少なくとも1つの要素について `predicate` が nil であれば `t` を、すべての要素が述語を満たせば `nil` を返します。`every` の補集合にあたります。空リストの場合は `nil` を返します。単一リスト形式のみ対応します。

```lisp
(notevery #'evenp '(2 4 5)) ; => t
```
