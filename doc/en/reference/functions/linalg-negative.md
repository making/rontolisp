# linalg:negative

`(linalg:negative array)`

Returns a fresh array of the same shape with every element negated (numpy's `np.negative`) -- equivalent to `(linalg:emap (lambda (x) (- x)) array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg).

```lisp
(linalg:negative #(1 -2 3)) ; => #d(-1.0 2.0 -3.0)
```
