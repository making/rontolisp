# bit-test

`(bit-test x n)`

Whether bit `n` of `x` is set, like the oracle's: `n` is masked to 6 bits, so `(bit-test 5
64)` is true. Anything but a 64-bit integer is refused (`bit operation not supported for:
class ...`; nil is a `NullPointerException`). As a value two arguments.

```clojure
(println (bit-test 5 0) (bit-test 5 1)) ; true false
```
