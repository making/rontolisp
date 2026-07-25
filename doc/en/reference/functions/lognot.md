# lognot

`(lognot integer)`

Returns the bitwise NOT (ones' complement) of `integer`, equivalent to `(- (+ integer 1))`. On the interpreter and JVM the operation is exact for arbitrarily large integers; on WASM the operation is exact within the signed 64-bit range.

```lisp
(lognot 5) ; => -6
```
