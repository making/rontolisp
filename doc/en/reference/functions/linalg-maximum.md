# linalg:maximum

`(linalg:maximum a b)`

Returns a fresh array with the element-wise larger of `a` and `b` (numpy's `np.maximum`); either operand may be a scalar, broadcast over the other's shape. It is defined by the strict comparison `(if (> x y) x y)`, not by an IEEE min/max primitive: the second operand wins whenever the comparison is false, which covers ties (a `-0.0` element against `0.0` takes the second operand) and unordered `NaN` comparisons (`(linalg:maximum nan-array b)` takes `b`'s elements, the reverse keeps the `NaN`s). The same rule on every backend. As a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg).

```lisp
(linalg:maximum #d(1.0 5.0 3.0) #d(4.0 2.0 3.0)) ; => #d(4.0 5.0 3.0)
(linalg:maximum #d(1.0 5.0 3.0) 2.5) ; => #d(2.5 5.0 3.0)
```
