# logxor

`(logxor &rest integers)`

Variadic bitwise exclusive OR of its integer arguments. With no arguments it returns `0` (the identity). The operation is exact for arbitrarily large integers on every backend.

```lisp
(logxor 12 10) ; => 6
```
