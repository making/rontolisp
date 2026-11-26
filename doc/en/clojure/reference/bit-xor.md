# bit-xor

`(bit-xor x y & more)`

The operands XORed as 64-bit two's-complement integers, like the oracle's. Anything but a
64-bit integer is refused (`bit operation not supported for: class ...`; nil is a
`NullPointerException`). As a value two or more arguments.

```clojure
(println (bit-xor 5 3) (bit-xor 5 6)) ; 6 3
```
