# linalg:asin

`(linalg:asin array)`

Returns a fresh array of the same shape with the arc sine applied to every element (numpy's `np.arcsin`) -- equivalent to `(linalg:emap #'asin array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`asin`](asin-acos-atan.md) itself, the WASM backends compute it with a software approximation whose low-order digits can differ from the interpreter's and the JVM's.

```lisp
(linalg:asin (linalg:zeros 3)) ; => #d(0.0 0.0 0.0)
```
