# nset-difference

`(nset-difference list-1 list-2 &key test test-not key)`

`list-2` に対応する要素がない `list-1` の要素。[`set-difference`](set-difference.md) と同じです。引数は変更されません([`nunion`](nunion.md) を参照)。

```lisp
(nset-difference (list 1 2 3) (list 2 3 4)) ; => (1)
```

```lisp
(nset-difference (list 1 2) (list 1 2)) ; => NIL
```
