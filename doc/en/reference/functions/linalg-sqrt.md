# linalg:sqrt

`(linalg:sqrt array)`

Returns a fresh array of the same shape with the square root of every element (numpy's `np.sqrt`) -- equivalent to `(linalg:emap #'sqrt array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg).

```lisp
(linalg:sqrt #(4 9 16)) ; => #d(2.0 3.0 4.0)
```
