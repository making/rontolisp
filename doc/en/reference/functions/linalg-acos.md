# linalg:acos

`(linalg:acos array)`

Returns a fresh array of the same shape with the arc cosine applied to every element (numpy's `np.arccos`) -- equivalent to `(linalg:emap #'acos array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`acos`](asin-acos-atan.md) itself, the WASM backends compute it with a software approximation whose low-order digits can differ from the interpreter's and the JVM's.

```lisp
(linalg:acos (linalg:ones 3)) ; => #d(0.0 0.0 0.0)
```
