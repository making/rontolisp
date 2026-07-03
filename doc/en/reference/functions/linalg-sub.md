# linalg:sub

`(linalg:sub a b)`

Subtracts `b` from `a` elementwise, returning a fresh array. Either operand may be a scalar, which is broadcast over the other operand's shape; two array operands must have equal shapes (a mismatch signals an error). See also [`linalg:add`](linalg-add.md), [`linalg:mul`](linalg-mul.md) and [`linalg:div`](linalg-div.md).

```lisp
(linalg:sub (linalg:from-list '(5 5)) 1) ; => #(4 4)
```
