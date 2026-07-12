# linalg:argmin

`(linalg:argmin array &optional axis)`

`axis` なしではベクタの最小の要素の 0 始まりのインデックスを返します。同値の場合は最初のインデックスを採用します。`axis` なしで受け付けるのはベクタのみで、空のベクタはエラーを通知します。整数の `axis` を渡したときの規則は [`linalg:argmax`](linalg-argmax.md) の比較を反転したものです (軸に沿ったスライスごとのインデックス。rank >= 2 の結果はインデックス値の packed DOUBLE 配列、ベクタは整数のインデックスに還元)。値そのもの (および行列全体のサポート) には [`linalg:amin`](linalg-amin.md) を使ってください。

```lisp
(linalg:argmin #(5 2 8)) ; => 1
(linalg:argmin #2A((1 9 3) (7 5 6)) 1) ; => #d(0.0 1.0)
```
