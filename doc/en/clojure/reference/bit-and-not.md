# bit-and-not

`(bit-and-not x & ys)`

`x` ANDed with each member of `ys` complemented, as 64-bit two's-complement integers, like
the oracle's. Anything but a 64-bit integer is refused (`bit operation not supported for:
class ...`; nil is a `NullPointerException`). As a value two or more arguments.

```clojure
(println (bit-and-not 5 3) (bit-and-not 1 2 3)) ; 4 0
```
