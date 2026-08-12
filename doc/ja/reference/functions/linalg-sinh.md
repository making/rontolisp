# linalg:sinh

`(linalg:sinh array)`

すべての要素に双曲線正弦を適用した、同じ形状の新しい配列を返します（numpy の `np.sinh`）。`(linalg:emap #'sinh array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。[`sinh`](sinh-cosh-tanh.md) 自体と同じく、WASM バックエンドはソフトウェア近似で計算するため、下位桁がインタプリタ・JVM と異なることがあります。

```lisp
(linalg:sinh (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
