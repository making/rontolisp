# nintersection

`(nintersection list-1 list-2 &key test test-not key)`

2 つのリストの積集合。[`intersection`](intersection.md) と同じです。引数は変更されません([`nunion`](nunion.md) を参照)。結果の順序は未規定です。

```lisp
(nintersection (list 1 2 3) (list 2 3 4)) ; => (3 2)
```

```lisp
(nintersection (list 1 2 3) (list 4 5)) ; => NIL
```
