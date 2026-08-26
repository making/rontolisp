# ldiff

`(ldiff list object)`

Returns a fresh list of the elements of `list` that precede `object`, where `object` is compared with `eql` against each successive tail. If `object` is not a tail of `list`, the whole list is copied -- including a dotted tail.

```lisp
(let ((l '(1 2 3 4))) (ldiff l (cddr l))) ; => (1 2)
```
