# linalg:-

`(linalg:- a &rest arrays)`

Subtracts the remaining arguments from `a` elementwise, left to right, and returns a fresh array. It is the CL operator spelling of [`linalg:sub`](linalg-sub.md), broadcasting by the same numpy rules. With a single argument it negates, exactly like CL `-`.

```lisp
(linalg:- #(5 5) 1)         ; => #d(4.0 4.0)
(linalg:- #(10 10) 1 2)     ; => #d(7.0 7.0)
(linalg:- #(5 5))           ; => #d(-5.0 -5.0)
```
