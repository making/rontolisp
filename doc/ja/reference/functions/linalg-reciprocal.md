# linalg:reciprocal

`(linalg:reciprocal array)`

すべての要素の逆数 `1 / x` を float で計算した、同じ形状の新しい配列を返します（float に対する numpy の `np.reciprocal`）。numpy 互換の名前を持つ `(linalg:div 1 array)` であり、[`linalg:div`](linalg-div.md) の [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) カーネルにそのまま乗ります。要素が 0 のときは numpy の float 意味論と同じく無限大になります。

```lisp
(linalg:reciprocal #(2 4 8)) ; => #d(0.5 0.25 0.125)
```
