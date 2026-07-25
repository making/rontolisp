# logorc2

`(logorc2 integer1 integer2)`

Bitwise inclusive OR of `integer1` with the complement of `integer2`, i.e. `(logior integer1 (lognot integer2))`. The operation is exact for arbitrarily large integers on every backend.

```lisp
(logorc2 12 10) ; => -3
```
