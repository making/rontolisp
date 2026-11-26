# linalg:tan

`(linalg:tan array)`

Returns a fresh array of the same shape with the tangent applied to every element (numpy's `np.tan`) -- equivalent to `(linalg:emap #'tan array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`tan`](sin-cos-tan.md) itself, it is fdlibm's on every backend, so the digits agree everywhere.

```lisp
(linalg:tan (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
