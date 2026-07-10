# linalg:exp

`(linalg:exp array)`

Returns a fresh array of the same shape with `e^x` applied to every element (numpy's `np.exp`) -- equivalent to `(linalg:emap #'exp array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`exp`](exp.md) itself, the WASM backends compute it with a software approximation whose low-order digits can differ from the interpreter's and the JVM's.

```lisp
(linalg:exp (linalg:zeros 3)) ; => #d(1.0 1.0 1.0)
```
