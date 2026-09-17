# linalg:tan

`(linalg:tan array)`

すべての要素に正接を適用した、同じ形状の新しい配列を返します（numpy の `np.tan`）。`(linalg:emap #'tan array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。[`tan`](sin-cos-tan.md) 自体と同じく、どのバックエンドでも fdlibm の値なので、桁はどこでも一致します。

```lisp
(linalg:tan (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
