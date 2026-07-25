# logbitp

`(logbitp index integer)`

Tests bit `index` (0 = least significant bit) of the two's-complement `integer`, returning `t` when it is set and `nil` otherwise. A negative `integer` has infinitely many high one-bits, so `(logbitp index -1)` is `t` for every `index`. On the interpreter and JVM `integer` may be any magnitude; on WASM bits are read within the signed 64-bit range (an index past 63 reads the sign bit).

```lisp
(logbitp 2 5) ; => T
```

```lisp
(logbitp 1 5) ; => NIL
```
