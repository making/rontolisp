# linalg:sinh

`(linalg:sinh array)`

Returns a fresh array of the same shape with the hyperbolic sine applied to every element (numpy's `np.sinh`) -- equivalent to `(linalg:emap #'sinh array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`sinh`](sinh-cosh-tanh.md) itself, the WASM backends compute it with a software approximation whose low-order digits can differ from the interpreter's and the JVM's.

```lisp
(linalg:sinh (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
