# linalg:sign

`(linalg:sign array)`

Returns a fresh array of the same shape with the sign of every element as `-1.0` / `0.0` / `1.0` (numpy's `np.sign`) -- equivalent to `(linalg:emap #'signum array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). It follows [`signum`](signum.md)'s own edges on each backend, so a `-0.0` element (which the interpreter and JVM keep as `-0.0` and WASM maps to `0.0`) is best avoided in cross-backend output.

```lisp
(linalg:sign #(-5 0 7)) ; => #d(-1.0 0.0 1.0)
```
