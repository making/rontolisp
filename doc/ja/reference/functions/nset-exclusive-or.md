# nset-exclusive-or

`(nset-exclusive-or list-1 list-2 &key test test-not key)`

2 つのリストの対称差。[`set-exclusive-or`](set-exclusive-or.md) と同じです。引数は変更されません([`nunion`](nunion.md) を参照)。

```lisp
(nset-exclusive-or (list 1 2 3) (list 2 3 4)) ; => (1 4)
```

```lisp
(nset-exclusive-or (list 1) (list 1)) ; => NIL
```
