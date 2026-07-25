# logorc2

`(logorc2 integer1 integer2)`

Bitwise inclusive OR of `integer1` with the complement of `integer2`, i.e. `(logior integer1 (lognot integer2))`. On the interpreter and JVM the operation is exact for arbitrarily large integers; on WASM the operation is exact within the signed 64-bit range.

```lisp
(logorc2 12 10) ; => -3
```
