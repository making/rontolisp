# linalg:minimum

`(linalg:minimum a b)`

`a` と `b` の要素ごとに小さい方を集めた新しい配列を返します（numpy の `np.minimum`）。どちらか一方の被演算子はスカラーでもよく、他方の形状にブロードキャストされます。[`linalg:maximum`](linalg-maximum.md) の鏡像で、`(if (< x y) x y)` で定義されるため、比較が偽になるとき（タイや `NaN` を含む）は常に第 2 被演算子が選ばれます。名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。

```lisp
(linalg:minimum #d(1.0 5.0 3.0) #d(4.0 2.0 3.0)) ; => #d(1.0 2.0 3.0)
(linalg:minimum 4.0 #d(1.0 5.0 3.0)) ; => #d(1.0 4.0 3.0)
```
