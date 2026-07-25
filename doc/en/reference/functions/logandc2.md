# logandc2

`(logandc2 integer1 integer2)`

Bitwise AND of `integer1` with the complement of `integer2`, i.e. `(logand integer1 (lognot integer2))`. The operation is exact for arbitrarily large integers on every backend.

```lisp
(logandc2 12 10) ; => 4
```
