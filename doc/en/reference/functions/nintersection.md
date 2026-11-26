# nintersection

`(nintersection list-1 list-2 &key test test-not key)`

The set intersection of two lists, like [`intersection`](intersection.md). The arguments are not modified -- see [`nunion`](nunion.md). The result order is unspecified.

```lisp
(nintersection (list 1 2 3) (list 2 3 4)) ; => (3 2)
```

```lisp
(nintersection (list 1 2 3) (list 4 5)) ; => NIL
```
