# linalg:relu

`(linalg:relu a)`

Returns a fresh array with every element replaced by `max(x, 0.0)` -- the rectified linear unit, the most common neural-network activation. Defined as `(linalg:maximum a 0.0)`, so a `-0.0` or `NaN` element becomes `0.0` (the strict comparison's false arm). Rides the [`linalg:maximum`](linalg-maximum.md) kernel under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg).

```lisp
(linalg:relu #d(-2.0 -0.0 3.0)) ; => #d(0.0 0.0 3.0)
```
