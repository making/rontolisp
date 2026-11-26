# linalg:log

`(linalg:log array)`

Returns a fresh array of the same shape with the natural logarithm applied to every element (numpy's `np.log`) -- equivalent to `(linalg:emap #'log array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`log`](log.md) itself, it is fdlibm's on every backend, so the digits agree everywhere.

```lisp
(linalg:log #(1 1 1)) ; => #d(0.0 0.0 0.0)
```
