# lognand

`(lognand integer1 integer2)`

Bitwise NAND of `integer1` with `integer2`, i.e. `(lognot (logand integer1 integer2))`. Exactly two arguments; the operation is exact for arbitrarily large integers on every backend.

```lisp
(lognand 12 10) ; => -9
```
