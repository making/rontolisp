# linalg:atan

`(linalg:atan array)`

Returns a fresh array of the same shape with the arc tangent applied to every element (numpy's `np.arctan`) -- equivalent to `(linalg:emap #'atan array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`atan`](asin-acos-atan.md) itself, the WASM backends compute it with a software approximation whose low-order digits can differ from the interpreter's and the JVM's.

```lisp
(linalg:atan (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
