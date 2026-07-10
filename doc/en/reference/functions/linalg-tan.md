# linalg:tan

`(linalg:tan array)`

Returns a fresh array of the same shape with the tangent applied to every element (numpy's `np.tan`) -- equivalent to `(linalg:emap #'tan array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`tan`](sin-cos-tan.md) itself, the WASM backends compute it with a software approximation whose low-order digits can differ from the interpreter's and the JVM's (and the divergence is amplified near the poles, where the cosine crosses zero).

```lisp
(linalg:tan (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
