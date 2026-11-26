# nset-difference

`(nset-difference list-1 list-2 &key test test-not key)`

The elements of `list-1` with no match in `list-2`, like [`set-difference`](set-difference.md). The arguments are not modified -- see [`nunion`](nunion.md).

```lisp
(nset-difference (list 1 2 3) (list 2 3 4)) ; => (1)
```

```lisp
(nset-difference (list 1 2) (list 1 2)) ; => NIL
```
