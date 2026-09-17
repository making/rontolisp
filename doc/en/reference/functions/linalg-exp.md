# linalg:exp

`(linalg:exp array)`

Returns a fresh array of the same shape with `e^x` applied to every element (numpy's `np.exp`) -- equivalent to `(linalg:emap #'exp array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`exp`](exp.md) itself, it is fdlibm's on every backend, so the digits agree everywhere.

```lisp
(linalg:exp (linalg:zeros 3)) ; => #d(1.0 1.0 1.0)
```
