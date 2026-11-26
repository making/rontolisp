# logior

`(logior &rest integers)`

Variadic bitwise inclusive OR of its integer arguments. With no arguments it returns `0` (the identity). The operation is exact for arbitrarily large integers on every backend.

```lisp
(logior 1 2 4 8) ; => 15
```
