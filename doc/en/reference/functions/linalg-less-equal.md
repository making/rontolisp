# linalg:less-equal

`(linalg:less-equal a b)`

Returns the elementwise `a <= b` comparison as a `0.0`/`1.0` mask (numpy's `<=`, which produces a boolean array); either operand may be a scalar, and arrays broadcast by numpy's rules exactly as [`linalg:add`](linalg-add.md) describes. Multiply by the mask where numpy would boolean-index. The siblings are [`linalg:less`](linalg-less.md), [`linalg:greater`](linalg-greater.md), [`linalg:greater-equal`](linalg-greater-equal.md) and [`linalg:equal`](linalg-equal.md).

```lisp
(linalg:less-equal #(1 5 3) 3) ; => #d(1.0 0.0 1.0)
```
