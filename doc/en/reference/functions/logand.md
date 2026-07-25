# logand

`(logand &rest integers)`

Variadic bitwise AND of its integer arguments. With no arguments it returns `-1` (the identity, all bits set). On the interpreter and JVM the operation is exact for arbitrarily large integers; on WASM the operation is exact within the signed 64-bit range.

```lisp
(logand 12 10) ; => 8
```
