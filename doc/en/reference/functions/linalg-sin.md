# linalg:sin

`(linalg:sin array)`

Returns a fresh array of the same shape with the sine applied to every element (numpy's `np.sin`) -- equivalent to `(linalg:emap #'sin array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`sin`](sin-cos-tan.md) itself, it is fdlibm's on every backend, so the digits agree everywhere.

```lisp
(linalg:sin (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
