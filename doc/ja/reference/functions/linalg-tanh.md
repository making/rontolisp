# linalg:tanh

`(linalg:tanh array)`

すべての要素に双曲線正接を適用した、同じ形状の新しい配列を返します（numpy の `np.tanh`）。`(linalg:emap #'tanh array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化され、パックド配列上のニューラルネットワークコードの活性化関数の第一候補になります。[`tanh`](sinh-cosh-tanh.md) 自体と同じく、WASM バックエンドはソフトウェア近似で計算するため、下位桁がインタプリタ・JVM と異なることがあります。

```lisp
(linalg:tanh (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
