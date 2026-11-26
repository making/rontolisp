# nset-exclusive-or

`(nset-exclusive-or list-1 list-2 &key test test-not key)`

The symmetric difference of two lists, like [`set-exclusive-or`](set-exclusive-or.md). The arguments are not modified -- see [`nunion`](nunion.md).

```lisp
(nset-exclusive-or (list 1 2 3) (list 2 3 4)) ; => (1 4)
```

```lisp
(nset-exclusive-or (list 1) (list 1)) ; => NIL
```
