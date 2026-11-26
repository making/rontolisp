# nsubst

`(nsubst new old tree &key test test-not key)`

Tree substitution, like [`subst`](subst.md). CL permits `nsubst` to overwrite the tree in place; rontolisp answers the non-destructive result instead, which the standard allows -- and unchanged subtrees are already shared with the original rather than copied.

```lisp
(nsubst 'x 'a (list 'a (list 'b 'a) 'c)) ; => (X (B X) C)
```
