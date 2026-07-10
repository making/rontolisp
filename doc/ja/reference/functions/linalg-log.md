# linalg:log

`(linalg:log array)`

すべての要素に自然対数を適用した、同じ形状の新しい配列を返します（numpy の `np.log`）。`(linalg:emap #'log array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。[`log`](log.md) 自体と同じく、WASM バックエンドはソフトウェア近似で計算するため、下位桁がインタプリタ・JVM と異なることがあります。

```lisp
(linalg:log #(1 1 1)) ; => #d(0.0 0.0 0.0)
```
