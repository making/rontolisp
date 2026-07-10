# linalg:sqrt

`(linalg:sqrt array)`

すべての要素の平方根をとった、同じ形状の新しい配列を返します（numpy の `np.sqrt`）。`(linalg:emap #'sqrt array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。

```lisp
(linalg:sqrt #(4 9 16)) ; => #d(2.0 3.0 4.0)
```
