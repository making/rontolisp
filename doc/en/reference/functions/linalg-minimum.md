# linalg:minimum

`(linalg:minimum a b)`

Returns a fresh array with the element-wise smaller of `a` and `b` (numpy's `np.minimum`); the operands broadcast by numpy's rules, exactly as [`linalg:add`](linalg-add.md) describes (a scalar over the other operand's shape, arrays of different shapes along their trailing axes). The mirror of [`linalg:maximum`](linalg-maximum.md): defined by `(if (< x y) x y)`, so the second operand wins whenever the comparison is false (ties and `NaN` included). As a named function it is accelerated under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg).

```lisp
(linalg:minimum #d(1.0 5.0 3.0) #d(4.0 2.0 3.0)) ; => #d(1.0 2.0 3.0)
(linalg:minimum 4.0 #d(1.0 5.0 3.0)) ; => #d(1.0 4.0 3.0)
```
