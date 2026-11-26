# nsublis

`(nsublis alist tree &key key test test-not)`

Alist-driven tree substitution, like [`sublis`](sublis.md). The tree is not modified -- see [`nsubst`](nsubst.md).

```lisp
(nsublis (list (cons 'a 1)) (list 'a (list 'b 'a))) ; => (1 (B 1))
```
