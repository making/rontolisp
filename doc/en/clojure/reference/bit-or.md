# bit-or

`(bit-or x y & more)`

The operands ORed as 64-bit two's-complement integers, like the oracle's. Anything but a
64-bit integer is refused (`bit operation not supported for: class ...`; nil is a
`NullPointerException`). As a value two or more arguments.

```clojure
(println (bit-or 5 3) (bit-or 0 0)) ; 7 0
```
