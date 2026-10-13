# bit-set

`(bit-set x n)`

`x` with bit `n` set, as a 64-bit two's-complement integer, like the oracle's: `n` is masked
to 6 bits, so `(bit-set 5 64)` is `5`. Anything but a 64-bit integer is refused (`bit
operation not supported for: class ...`; nil is a `NullPointerException`). As a value two
arguments.

```clojure
(println (bit-set 0 3) (bit-set 5 64)) ; 8 5
```
