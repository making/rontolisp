# linalg:exp

`(linalg:exp array)`

すべての要素に `e^x` を適用した、同じ形状の新しい配列を返します（numpy の `np.exp`）。`(linalg:emap #'exp array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。[`exp`](exp.md) 自体と同じく、WASM バックエンドはソフトウェア近似で計算するため、下位桁がインタプリタ・JVM と異なることがあります。

```lisp
(linalg:exp (linalg:zeros 3)) ; => #d(1.0 1.0 1.0)
```
