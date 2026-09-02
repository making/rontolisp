# linalg:sign

`(linalg:sign array)`

Returns a fresh array of the same shape with the sign of every element as `-1.0` / `0.0` / `1.0` (numpy's `np.sign`) -- equivalent to `(linalg:emap #'signum array)`, but as a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg). It follows [`signum`](signum.md)'s own edges, which agree on every backend: a `-0.0` element stays `-0.0` and a `NaN` element stays `NaN`, since `signum` answers its argument unchanged for both.

```lisp
(linalg:sign #(-5 0 7)) ; => #d(-1.0 0.0 1.0)
```
