# logandc1

`(logandc1 integer1 integer2)`

Bitwise AND of the complement of `integer1` with `integer2`, i.e. `(logand (lognot integer1) integer2)`. On the interpreter and JVM the operation is exact for arbitrarily large integers; on WASM operands are 31-bit `i31` values.

```lisp
(logandc1 12 10) ; => 2
```
