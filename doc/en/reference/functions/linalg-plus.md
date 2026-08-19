# linalg:+

`(linalg:+ &rest arrays)`

Sums its arguments elementwise, left to right, and returns a fresh array. It is the CL operator spelling of [`linalg:add`](linalg-add.md): every fold step broadcasts by the same numpy rules, so scalars and shape-compatible arrays mix freely. With no argument it returns `0`, and with one argument it returns that argument unchanged.

```lisp
(linalg:+ #(1 2 3) 10)             ; => #d(11.0 12.0 13.0)
(linalg:+ #(1 2) #(3 4) #(10 10))  ; => #d(14.0 16.0)
(linalg:+)                         ; => 0
```
