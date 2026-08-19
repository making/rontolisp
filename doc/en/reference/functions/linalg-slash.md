# linalg:/

`(linalg:/ a &rest arrays)`

Divides `a` by the remaining arguments elementwise, left to right, and returns a fresh array. It is the CL operator spelling of [`linalg:div`](linalg-div.md), broadcasting by the same numpy rules. With a single argument it returns the reciprocal, exactly like CL `/`.

```lisp
(linalg:/ #(1 2 3) 2)         ; => #d(0.5 1.0 1.5)
(linalg:/ #(12 12) 2 3)       ; => #d(2.0 2.0)
(linalg:/ #(1.0 2.0 4.0))     ; => #d(1.0 0.5 0.25)
```
