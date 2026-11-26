# linalg:cosh

`(linalg:cosh array)`

Returns a fresh array of the same shape with the hyperbolic cosine applied to every element (numpy's `np.cosh`) -- equivalent to `(linalg:emap #'cosh array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`cosh`](sinh-cosh-tanh.md) itself, it is fdlibm's on every backend, so the digits agree everywhere.

```lisp
(linalg:cosh (linalg:zeros 3)) ; => #d(1.0 1.0 1.0)
```
