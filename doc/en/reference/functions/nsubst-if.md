# nsubst-if

`(nsubst-if new predicate tree &key key)`

Like [`subst-if`](subst-if.md). The tree is not modified -- see [`nsubst`](nsubst.md).

```lisp
(nsubst-if 0 #'numberp (list 1 (list 2 'x))) ; => (0 (0 X))
```
