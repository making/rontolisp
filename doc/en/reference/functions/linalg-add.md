# linalg:add

`(linalg:add a b)`

Adds `a` and `b` elementwise, returning a fresh array. Either operand may be a scalar, which is broadcast over the other operand's shape; two array operands must have equal shapes (a mismatch signals an error). The other elementwise operators are [`linalg:sub`](linalg-sub.md), [`linalg:mul`](linalg-mul.md) and [`linalg:div`](linalg-div.md).

```lisp
(linalg:add #(1 2 3) 10)   ; => #(11 12 13)
(linalg:add #(1 2) #(3 4)) ; => #(4 6)
```
