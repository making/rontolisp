# nsubst-if-not

`(nsubst-if-not new predicate tree &key key)`

Like [`subst-if-not`](subst-if-not.md). The tree is not modified -- see [`nsubst`](nsubst.md).

```lisp
(nsubst-if-not 0 #'listp (list 1 (list 2))) ; => (0 (0))
```
