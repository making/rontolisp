# linalg:tanh

`(linalg:tanh array)`

Returns a fresh array of the same shape with the hyperbolic tangent applied to every element (numpy's `np.tanh`) -- equivalent to `(linalg:emap #'tanh array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg), which makes it the go-to activation function for neural-network code over packed arrays. Like [`tanh`](sinh-cosh-tanh.md) itself, it is fdlibm's on every backend, so the digits agree everywhere.

```lisp
(linalg:tanh (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
