# bit-not

`(bit-not x)`

`x` complemented as a 64-bit two's-complement integer, like the oracle's. Anything but a
64-bit integer is refused (`bit operation not supported for: class ...`; nil is a
`NullPointerException`). As a value one argument.

```clojure
(println (bit-not 5) (bit-not 0)) ; -6 -1
```
