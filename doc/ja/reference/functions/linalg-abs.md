# linalg:abs

`(linalg:abs array)`

すべての要素の絶対値をとった、同じ形状の新しい配列を返します（numpy の `np.abs`）。`(linalg:emap #'abs array)` と等価ですが、名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。

```lisp
(linalg:abs #(-3 2 -1)) ; => #d(3.0 2.0 1.0)
```
