# linalg:sqrt

`(linalg:sqrt array)`

すべての要素の平方根をとった、同じ形状の新しい配列を返します（numpy の `np.sqrt`）。名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。負の要素は複素数ではなく `NaN` を返します。要素関数は浮動小数点領域の平方根です（CL の `sqrt` は負数を複素平面に写しますが、複素数は packed 要素ストアに格納できません）。

```lisp
(linalg:sqrt #(4 9 16)) ; => #d(2.0 3.0 4.0)
```
