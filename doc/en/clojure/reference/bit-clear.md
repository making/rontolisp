# bit-clear

`(bit-clear x n)`

`x` with bit `n` cleared, as a 64-bit two's-complement integer, like the oracle's: `n` is
masked to 6 bits, so `(bit-clear 5 64)` is `4`. Anything but a 64-bit integer is refused
(`bit operation not supported for: class ...`; nil is a `NullPointerException`). As a value
two arguments.

```clojure
(println (bit-clear 7 1) (bit-clear 5 64)) ; 5 4
```
