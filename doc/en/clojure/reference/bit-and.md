# bit-and

`(bit-and x y & more)`

The operands ANDed as 64-bit two's-complement integers, like the oracle's. Anything but a
64-bit integer is refused (`bit operation not supported for: class ...`; nil is a
`NullPointerException`). An `N`-suffixed literal in range reads as a plain integer here, so
`(bit-and 5N 2)` answers where the oracle refuses its `BigInt`. As a value two or more
arguments.

```clojure
(println (bit-and 5 3) (bit-and 1 2 3 4)) ; 1 0
```
