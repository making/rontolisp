# linalg:argmax

`(linalg:argmax array &optional axis)`

`axis` なしではベクタの最大の要素の 0 始まりのインデックスを返します。同値の場合は最初のインデックスを採用します。`axis` なしで受け付けるのはベクタのみで、空のベクタはエラーを通知します。整数の `axis` (負の値は numpy の規則で末尾から数えます) を渡すと、その軸に沿ったスライスごとのインデックスを返し、軸は結果から除去されます。rank >= 2 の結果はインデックス値の packed DOUBLE 配列です (linalg 配列に整数幅はありませんが、比較には `(= 3.0 3)` が成り立ちます)。ベクタは整数のインデックスそのものに還元されます。値そのもの (および行列全体のサポート) には [`linalg:amax`](linalg-amax.md) を使ってください。対応物は [`linalg:argmin`](linalg-argmin.md) です。

```lisp
(linalg:argmax #(1 9 3)) ; => 1
(linalg:argmax #2A((1 9 3) (7 5 6)) 1) ; => #d(1.0 0.0)
```
