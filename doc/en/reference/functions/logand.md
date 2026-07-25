# logand

`(logand &rest integers)`

Variadic bitwise AND of its integer arguments. With no arguments it returns `-1` (the identity, all bits set). The operation is exact for arbitrarily large integers on every backend.

```lisp
(logand 12 10) ; => 8
```
