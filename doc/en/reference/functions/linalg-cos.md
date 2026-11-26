# linalg:cos

`(linalg:cos array)`

Returns a fresh array of the same shape with the cosine applied to every element (numpy's `np.cos`) -- equivalent to `(linalg:emap #'cos array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`cos`](sin-cos-tan.md) itself, it is fdlibm's on every backend, so the digits agree everywhere.

```lisp
(linalg:cos (linalg:zeros 3)) ; => #d(1.0 1.0 1.0)
```
