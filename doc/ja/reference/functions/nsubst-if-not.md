# nsubst-if-not

`(nsubst-if-not new predicate tree &key key)`

[`subst-if-not`](subst-if-not.md) と同じです。木は変更されません([`nsubst`](nsubst.md) を参照)。

```lisp
(nsubst-if-not 0 #'listp (list 1 (list 2))) ; => (0 (0))
```
