# linalg:negative

`(linalg:negative array)`

すべての要素の符号を反転した、同じ形状の新しい配列を返します（numpy の `np.negative`）。`(linalg:emap (lambda (x) (- x)) array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。

```lisp
(linalg:negative #(1 -2 3)) ; => #d(-1.0 2.0 -3.0)
```
