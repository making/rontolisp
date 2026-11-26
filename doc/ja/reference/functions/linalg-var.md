# linalg:var

`(linalg:var array &key axis keepdims ddof)`

分散 (平均からの偏差の二乗の平均) を返します。`:axis` なしではすべての要素について、`:axis` を渡すとその軸に沿って計算し、`:axis` / `:keepdims` の規則は [`linalg:sum`](linalg-sum.md) と同じです。除数は `n - ddof` です。デフォルトの `:ddof 0` は numpy の `np.var`、torch の `unbiased=False` に相当し、`:ddof 1` は標本分散 (ベッセルの補正) になります。平方根は [`linalg:std`](linalg-std.md) です。

```lisp
(linalg:var #(1 2 3 4))                   ; => 1.25
(linalg:var #(1 2 3 4) :ddof 1)           ; => 1.6666666666666667
(linalg:var #2A((0 1 2) (3 4 5)) :axis 1) ; => #d(0.6666666666666666 0.6666666666666666)
```
