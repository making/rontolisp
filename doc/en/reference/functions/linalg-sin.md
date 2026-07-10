# linalg:sin

`(linalg:sin array)`

Returns a fresh array of the same shape with the sine applied to every element (numpy's `np.sin`) -- equivalent to `(linalg:emap #'sin array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`sin`](sin-cos-tan.md) itself, the WASM backends compute it with a software approximation whose low-order digits can differ from the interpreter's and the JVM's.

```lisp
(linalg:sin (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
