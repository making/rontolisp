# linalg:mean

`(linalg:mean array)`

すべての要素の算術平均を返します。[`linalg:sum`](linalg-sum.md) を [`linalg:size`](linalg-size.md) で割った値です。整数の入力に対しては、浮動小数点数ではなく厳密な有理数の結果を返します。

```lisp
(linalg:mean #(1 2 3 4)) ; => 5/2
```
