# linalg:cosh

`(linalg:cosh array)`

すべての要素に双曲線余弦を適用した、同じ形状の新しい配列を返します（numpy の `np.cosh`）。`(linalg:emap #'cosh array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#linalg-のアクセラレーション) で高速化されます。[`cosh`](sinh-cosh-tanh.md) 自体と同じく、WASM バックエンドはソフトウェア近似で計算するため、下位桁がインタプリタ・JVM と異なることがあります。

```lisp
(linalg:cosh (linalg:zeros 3)) ; => #d(1.0 1.0 1.0)
```
