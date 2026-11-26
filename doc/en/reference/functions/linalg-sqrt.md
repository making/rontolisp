# linalg:sqrt

`(linalg:sqrt array)`

Returns a fresh array of the same shape with the square root of every element (numpy's `np.sqrt`), but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). A negative element answers `NaN`, not a complex: the element function is the float-domain square root (CL `sqrt` roots negatives into the complex plane, which no packed element store accepts).

```lisp
(linalg:sqrt #(4 9 16)) ; => #d(2.0 3.0 4.0)
```
