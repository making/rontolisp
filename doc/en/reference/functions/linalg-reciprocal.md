# linalg:reciprocal

`(linalg:reciprocal array)`

Returns a fresh array of the same shape with `1 / x` for every element, in float (numpy's `np.reciprocal` over floats) -- `(linalg:div 1 array)` under a numpy-parity name, so it rides [`linalg:div`](linalg-div.md)'s [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) kernels. A zero element yields infinity, as in numpy's float semantics.

```lisp
(linalg:reciprocal #(2 4 8)) ; => #d(0.5 0.25 0.125)
```
