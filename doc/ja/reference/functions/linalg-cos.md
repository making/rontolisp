# linalg:cos

`(linalg:cos array)`

すべての要素に余弦を適用した、同じ形状の新しい配列を返します（numpy の `np.cos`）。`(linalg:emap #'cos array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。[`cos`](sin-cos-tan.md) 自体と同じく、どのバックエンドでも fdlibm の値なので、桁はどこでも一致します。

```lisp
(linalg:cos (linalg:zeros 3)) ; => #d(1.0 1.0 1.0)
```
