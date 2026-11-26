# logeqv

`(logeqv &rest integers)`

Bitwise equivalence of `integers`, the left fold of the two-argument `(lognot (logxor x y))`. `(logeqv)` is `-1` and `(logeqv x)` is `x`. Each argument is evaluated once, left to right, and the operation is exact for arbitrarily large integers on every backend.

```lisp
(logeqv 12 10) ; => -7
```
