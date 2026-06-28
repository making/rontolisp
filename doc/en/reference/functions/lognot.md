# lognot

`(lognot integer)`

Returns the bitwise NOT (ones' complement) of `integer`, equivalent to `(- (+ integer 1))`. On the interpreter and JVM the operation is exact for arbitrarily large integers; on WASM the operand is a 31-bit `i31` value.

```lisp
(lognot 5) ; => -6
```
