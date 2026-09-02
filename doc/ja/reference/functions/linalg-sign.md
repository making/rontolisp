# linalg:sign

`(linalg:sign array)`

すべての要素の符号を `-1.0` / `0.0` / `1.0` で表した、同じ形状の新しい配列を返します（numpy の `np.sign`）。`(linalg:emap #'signum array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。[`signum`](signum.md) の端値挙動に従いますが、これはどのバックエンドでも一致します。`signum` は `-0.0` と `NaN` に対して引数をそのまま返すため、`-0.0` 要素は `-0.0` のまま、`NaN` 要素は `NaN` のままです。

```lisp
(linalg:sign #(-5 0 7)) ; => #d(-1.0 0.0 1.0)
```
