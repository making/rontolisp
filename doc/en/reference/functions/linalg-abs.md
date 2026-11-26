# linalg:abs

`(linalg:abs array)`

Returns a fresh array of the same shape with the absolute value of every element (numpy's `np.abs`) -- equivalent to `(linalg:emap #'abs array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg).

```lisp
(linalg:abs #(-3 2 -1)) ; => #d(3.0 2.0 1.0)
```
