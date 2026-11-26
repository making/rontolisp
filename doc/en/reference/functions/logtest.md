# logtest

`(logtest integer-1 integer-2)`

Tests whether `integer-1` and `integer-2` have any one-bits in common: equivalent to `(not (zerop (logand integer-1 integer-2)))`. Returns `t` when they share a set bit and `nil` otherwise. Both arguments may be any magnitude on every backend.

```lisp
(logtest 1 3) ; => T
```

```lisp
(logtest 1 2) ; => NIL
```
