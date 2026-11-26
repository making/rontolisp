# bit-shift-left

`(bit-shift-left x n)`

`x` shifted left `n` places as a 64-bit two's-complement integer, like the oracle's: `n` is
masked to 6 bits, so `(bit-shift-left 1 64)` is `1`, and the answer wraps, so
`(bit-shift-left 1 63)` is `-9223372036854775808`. Anything but a 64-bit integer is refused
(`bit operation not supported for: class ...`; nil is a `NullPointerException`). As a value
two arguments.

```clojure
(println (bit-shift-left 1 2) (bit-shift-left 1 64) (bit-shift-left 1 63)) ; 4 1 -9223372036854775808
```
