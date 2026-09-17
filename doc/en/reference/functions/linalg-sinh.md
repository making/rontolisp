# linalg:sinh

`(linalg:sinh array)`

Returns a fresh array of the same shape with the hyperbolic sine applied to every element (numpy's `np.sinh`) -- equivalent to `(linalg:emap #'sinh array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`sinh`](sinh-cosh-tanh.md) itself, it is fdlibm's on every backend, so the digits agree everywhere.

```lisp
(linalg:sinh (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
