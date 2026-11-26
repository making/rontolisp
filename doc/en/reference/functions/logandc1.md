# logandc1

`(logandc1 integer1 integer2)`

Bitwise AND of the complement of `integer1` with `integer2`, i.e. `(logand (lognot integer1) integer2)`. The operation is exact for arbitrarily large integers on every backend.

```lisp
(logandc1 12 10) ; => 2
```
