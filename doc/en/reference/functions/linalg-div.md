# linalg:div

`(linalg:div a b)`

Divides `a` by `b` elementwise, returning a fresh packed float array. The operands broadcast by numpy's rules, exactly as [`linalg:add`](linalg-add.md) describes: a scalar broadcasts over the other operand's shape, and two arrays of different shapes broadcast along their trailing axes when each aligned extent pair is equal or contains a 1.

```lisp
(linalg:div #(1 2 3) 2) ; => #d(0.5 1.0 1.5)
```
