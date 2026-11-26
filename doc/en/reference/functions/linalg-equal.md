# linalg:equal

`(linalg:equal a b)`

Returns the elementwise numeric equality of `a` and `b` as a `0.0`/`1.0` mask (numpy's `==`, which produces a boolean array); either operand may be a scalar, and arrays broadcast by numpy's rules exactly as [`linalg:add`](linalg-add.md) describes. Multiply by the mask where numpy would boolean-index. For a single boolean answer over the whole array, use [`linalg:array-equal`](linalg-array-equal.md); the ordering comparisons are [`linalg:greater`](linalg-greater.md), [`linalg:greater-equal`](linalg-greater-equal.md), [`linalg:less`](linalg-less.md) and [`linalg:less-equal`](linalg-less-equal.md).

```lisp
(linalg:equal #(1 5 3) #(2 5 1)) ; => #d(0.0 1.0 0.0)
```
