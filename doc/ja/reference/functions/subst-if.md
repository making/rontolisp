# subst-if

`(subst-if new predicate tree &key key)`

[`subst`](subst.md) と同様ですが、置換の判定は項目との一致ではなく `(funcall predicate subtree)` が真かどうかで行われます。`:key` は述語に渡す前の部分木に適用されるセレクタです。変更されない部分木は元の木と共有されます。

```lisp
(subst-if 0 #'numberp '(1 (2 x) 3)) ; => (0 (0 X) 0)
```
