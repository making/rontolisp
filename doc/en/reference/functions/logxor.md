# logxor

`(logxor &rest integers)`

Variadic bitwise exclusive OR of its integer arguments. With no arguments it returns `0` (the identity). On the interpreter and JVM the operation is exact for arbitrarily large integers; on WASM the operation is exact within the signed 64-bit range.

```lisp
(logxor 12 10) ; => 6
```
