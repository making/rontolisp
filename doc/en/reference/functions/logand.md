# logand

`(logand &rest integers)`

Variadic bitwise AND of its integer arguments. With no arguments it returns `-1` (the identity, all bits set). On the interpreter and JVM the operation is exact for arbitrarily large integers; on WASM operands are 31-bit `i31` values.

```lisp
(logand 12 10) ; => 8
```
