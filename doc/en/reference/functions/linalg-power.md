# linalg:power

`(linalg:power a b)`

Elementwise `a` raised to `b` (numpy's `np.power`, the `**` operator). Either operand may be a scalar and two arrays broadcast by the numpy rules, exactly like [`linalg:mul`](linalg-mul.md). Both operands go through the same float element model as the rest of `linalg`, so a fractional exponent is the ordinary float power.

```lisp
(linalg:power #(1 2 3) 2) ; => #d(1.0 4.0 9.0)
(linalg:power 2 #(1 2 3)) ; => #d(2.0 4.0 8.0)
```
