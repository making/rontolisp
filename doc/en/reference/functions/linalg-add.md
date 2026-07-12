# linalg:add

`(linalg:add a b)`

Adds `a` and `b` elementwise, returning a fresh array. The operands broadcast by numpy's rules: a scalar is broadcast over the other operand's shape, and two arrays of different shapes align their trailing axes -- each aligned pair of extents must be equal or contain a 1 (a missing leading axis counts as 1), and the axis of extent 1 stretches over the other operand's extent; anything else signals a shape-mismatch error. The result keeps the first array operand's element type. The other elementwise operators are [`linalg:sub`](linalg-sub.md), [`linalg:mul`](linalg-mul.md) and [`linalg:div`](linalg-div.md).

```lisp
(linalg:add #(1 2 3) 10)   ; => #d(11.0 12.0 13.0)
(linalg:add #(1 2) #(3 4)) ; => #d(4.0 6.0)
(linalg:add #2A((1 2) (3 4)) #2A((100) (200))) ; => #d((101.0 102.0) (203.0 204.0))
```
