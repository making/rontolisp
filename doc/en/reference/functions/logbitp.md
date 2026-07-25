# logbitp

`(logbitp index integer)`

Tests bit `index` (0 = least significant bit) of the two's-complement `integer`, returning `t` when it is set and `nil` otherwise. A negative `integer` has infinitely many high one-bits, so `(logbitp index -1)` is `t` for every `index`. `integer` may be any magnitude on every backend.

```lisp
(logbitp 2 5) ; => T
```

```lisp
(logbitp 1 5) ; => NIL
```
