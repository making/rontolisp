# nsubst-if

`(nsubst-if new predicate tree &key key)`

[`subst-if`](subst-if.md) と同じです。木は変更されません([`nsubst`](nsubst.md) を参照)。

```lisp
(nsubst-if 0 #'numberp (list 1 (list 2 'x))) ; => (0 (0 X))
```
