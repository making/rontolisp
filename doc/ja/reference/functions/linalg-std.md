# linalg:std

`(linalg:std array &key axis keepdims ddof)`

標準偏差、すなわち [`linalg:var`](linalg-var.md) の平方根を返します。`:axis`、`:keepdims`、`:ddof` の規則は同じです (numpy の `np.std`)。同じ軸の [`linalg:mean`](linalg-mean.md) と組み合わせると LayerNorm の正規化になります。

```lisp
(linalg:std #(2 4 4 4 5 5 7 9))           ; => 2.0
(linalg:std #2A((0 1 2) (3 4 5)) :axis 0) ; => #d(1.5 1.5 1.5)
```
