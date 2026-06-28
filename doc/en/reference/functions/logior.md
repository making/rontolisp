# logior

`(logior &rest integers)`

Variadic bitwise inclusive OR of its integer arguments. With no arguments it returns `0` (the identity). On the interpreter and JVM the operation is exact for arbitrarily large integers; on WASM operands are 31-bit `i31` values.

```lisp
(logior 1 2 4 8) ; => 15
```
