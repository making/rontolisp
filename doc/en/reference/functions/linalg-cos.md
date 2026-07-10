# linalg:cos

`(linalg:cos array)`

Returns a fresh array of the same shape with the cosine applied to every element (numpy's `np.cos`) -- equivalent to `(linalg:emap #'cos array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`cos`](sin-cos-tan.md) itself, the WASM backends compute it with a software approximation whose low-order digits can differ from the interpreter's and the JVM's.

```lisp
(linalg:cos (linalg:zeros 3)) ; => #d(1.0 1.0 1.0)
```
