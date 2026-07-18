# logorc1

`(logorc1 integer1 integer2)`

Bitwise inclusive OR of the complement of `integer1` with `integer2`, i.e. `(logior (lognot integer1) integer2)`. On the interpreter and JVM the operation is exact for arbitrarily large integers; on WASM operands are 31-bit `i31` values.

```lisp
(logorc1 12 10) ; => -5
```
