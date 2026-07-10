# linalg:log

`(linalg:log array)`

Returns a fresh array of the same shape with the natural logarithm applied to every element (numpy's `np.log`) -- equivalent to `(linalg:emap #'log array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). Like [`log`](log.md) itself, the WASM backends compute it with a software approximation whose low-order digits can differ from the interpreter's and the JVM's.

```lisp
(linalg:log #(1 1 1)) ; => #d(0.0 0.0 0.0)
```
