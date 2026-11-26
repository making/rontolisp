# linalg:square

`(linalg:square array)`

すべての要素をそれ自身と掛けた、同じ形状の新しい配列を返します（numpy の `np.square`）。numpy 互換の名前を持つ `(linalg:mul array array)` であり、[`linalg:mul`](linalg-mul.md) の [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) カーネルにそのまま乗ります。ただの数値を渡すとただの数値の 2 乗を返します。

```lisp
(linalg:square #(1 2 3)) ; => #d(1.0 4.0 9.0)
```
