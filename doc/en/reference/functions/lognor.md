# lognor

`(lognor integer1 integer2)`

Bitwise NOR of `integer1` with `integer2`, i.e. `(lognot (logior integer1 integer2))`. Exactly two arguments; the operation is exact for arbitrarily large integers on every backend.

```lisp
(lognor 12 10) ; => -15
```
