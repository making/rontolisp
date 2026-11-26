# linalg:acos

`(linalg:acos array)`

Returns a fresh array of the same shape with the arc cosine applied to every element (numpy's `np.arccos`) -- equivalent to `(linalg:emap #'acos array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`acos`](asin-acos-atan.md) itself, it is fdlibm's on every backend, so the digits agree everywhere.

```lisp
(linalg:acos (linalg:ones 3)) ; => #d(0.0 0.0 0.0)
```
