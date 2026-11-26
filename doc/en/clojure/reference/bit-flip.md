# bit-flip

`(bit-flip x n)`

`x` with bit `n` flipped, as a 64-bit two's-complement integer, like the oracle's: `n` is
masked to 6 bits, so `(bit-flip 5 64)` is `4`. Anything but a 64-bit integer is refused (`bit
operation not supported for: class ...`; nil is a `NullPointerException`). As a value two
arguments.

```clojure
(println (bit-flip 5 1) (bit-flip 5 64)) ; 7 4
```
