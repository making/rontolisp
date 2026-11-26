# linalg:relu

`(linalg:relu a)`

すべての要素を `max(x, 0.0)` に置き換えた新しい配列を返します — ニューラルネットワークで最も一般的な活性化関数 ReLU（rectified linear unit）です。`(linalg:maximum a 0.0)` として定義されるため、`-0.0` や `NaN` の要素は `0.0` になります（厳密比較の偽側）。[`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) では [`linalg:maximum`](linalg-maximum.md) のカーネルに乗って高速化されます。

```lisp
(linalg:relu #d(-2.0 -0.0 3.0)) ; => #d(0.0 0.0 3.0)
```
