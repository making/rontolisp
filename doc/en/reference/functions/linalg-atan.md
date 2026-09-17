# linalg:atan

`(linalg:atan array)`

Returns a fresh array of the same shape with the arc tangent applied to every element (numpy's `np.arctan`) -- equivalent to `(linalg:emap #'atan array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`atan`](asin-acos-atan.md) itself, it is fdlibm's on every backend, so the digits agree everywhere.

```lisp
(linalg:atan (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
