# linalg:sinh

`(linalg:sinh array)`

すべての要素に双曲線正弦を適用した、同じ形状の新しい配列を返します（numpy の `np.sinh`）。`(linalg:emap #'sinh array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。[`sinh`](sinh-cosh-tanh.md) 自体と同じく、どのバックエンドでも fdlibm の値なので、桁はどこでも一致します。

```lisp
(linalg:sinh (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
