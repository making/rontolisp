# unsigned-bit-shift-right

`(unsigned-bit-shift-right x n)`

`x`, read unsigned, shifted logically right `n` places, like the oracle's: `n` is masked to
6 bits and the answer wraps to 64 bits, so `(unsigned-bit-shift-right -1 0)` is `-1`.
Anything but a 64-bit integer is refused (`bit operation not supported for: class ...`; nil
is a `NullPointerException`). As a value two arguments.

```clojure
(println (unsigned-bit-shift-right -8 2) (unsigned-bit-shift-right -1 0)) ; 4611686018427387902 -1
```
