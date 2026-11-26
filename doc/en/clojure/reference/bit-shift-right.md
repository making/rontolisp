# bit-shift-right

`(bit-shift-right x n)`

`x` shifted arithmetically right `n` places as a 64-bit two's-complement integer, like the
oracle's: `n` is masked to 6 bits, so `(bit-shift-right 8 64)` is `8`. Anything but a 64-bit
integer is refused (`bit operation not supported for: class ...`; nil is a
`NullPointerException`). As a value two arguments.

```clojure
(println (bit-shift-right -8 2) (bit-shift-right 8 64)) ; -2 8
```
