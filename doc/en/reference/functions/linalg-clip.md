# linalg:clip

`(linalg:clip a lo hi)`

Returns a fresh array with every element limited to the `[lo, hi]` interval (numpy's `np.clip` with scalar bounds), defined as the composition `(linalg:minimum (linalg:maximum a lo) hi)`. Two consequences of that definition: a `NaN` element becomes `lo` (the first comparison is false, so the bound wins), and inverted bounds (`lo > hi`) send every element to `hi`. Rides the [`linalg:maximum`](linalg-maximum.md) / [`linalg:minimum`](linalg-minimum.md) kernels under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg).

```lisp
(linalg:clip #d(-2.0 0.5 3.0) -1.0 1.0) ; => #d(-1.0 0.5 1.0)
```
