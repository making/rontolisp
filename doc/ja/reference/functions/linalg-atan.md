# linalg:atan

`(linalg:atan array)`

すべての要素に逆正接を適用した、同じ形状の新しい配列を返します（numpy の `np.arctan`）。`(linalg:emap #'atan array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#linalg-のアクセラレーション) で高速化されます。[`atan`](asin-acos-atan.md) 自体と同じく、WASM バックエンドはソフトウェア近似で計算するため、下位桁がインタプリタ・JVM と異なることがあります。

```lisp
(linalg:atan (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
