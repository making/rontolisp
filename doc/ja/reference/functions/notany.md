# notany

`(notany predicate list)`

`list` のすべての要素について `predicate` が nil であれば `t` を、いずれかの要素が述語を満たせば `nil` を返します。`some` の補集合にあたります。空リストの場合は `t` を返します。単一リスト形式のみ対応します。

```lisp
(notany #'evenp '(1 3 5)) ; => t
```
