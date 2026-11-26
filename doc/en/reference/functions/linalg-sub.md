# linalg:sub

`(linalg:sub a b)`

Subtracts `b` from `a` elementwise, returning a fresh array. The operands broadcast by numpy's rules, exactly as [`linalg:add`](linalg-add.md) describes: a scalar broadcasts over the other operand's shape, and two arrays of different shapes broadcast along their trailing axes when each aligned extent pair is equal or contains a 1. See also [`linalg:mul`](linalg-mul.md) and [`linalg:div`](linalg-div.md).

```lisp
(linalg:sub #(5 5) 1) ; => #d(4.0 4.0)
```
