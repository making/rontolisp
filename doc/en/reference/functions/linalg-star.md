# linalg:*

`(linalg:* &rest arrays)`

Multiplies its arguments elementwise (the Hadamard product, **not** the matrix product -- that is [`linalg:matmul`](linalg-matmul.md)), left to right, and returns a fresh array. It is the CL operator spelling of [`linalg:mul`](linalg-mul.md), broadcasting by the same numpy rules. With no argument it returns `1`, and with one argument it returns that argument unchanged.

```lisp
(linalg:* #(1 2) #(3 4))          ; => #d(3.0 8.0)
(linalg:* #(1 2 3) 2 10)          ; => #d(20.0 40.0 60.0)
(linalg:*)                        ; => 1
```
