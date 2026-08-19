# linalg:power

`(linalg:power a b)`

要素ごとの `a` の `b` 乗です (numpy の `np.power`、`**` 演算子)。どちらのオペランドもスカラーでよく、2 つの配列は [`linalg:mul`](linalg-mul.md) と同様に numpy の規則でブロードキャストされます。両オペランドは `linalg` の他の関数と同じ浮動小数点の要素モデルを通るため、小数の指数は通常の浮動小数点のべき乗になります。

```lisp
(linalg:power #(1 2 3) 2) ; => #d(1.0 4.0 9.0)
(linalg:power 2 #(1 2 3)) ; => #d(2.0 4.0 8.0)
```
