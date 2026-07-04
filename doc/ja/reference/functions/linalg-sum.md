# linalg:sum

`(linalg:sum array)`

ベクタまたは行列のすべての要素の合計を返します。整数の入力に対しては厳密な整数の結果を返します。平均には [`linalg:mean`](linalg-mean.md) を使ってください。

```lisp
(linalg:sum #2A((1 2) (3 4))) ; => 10
```
