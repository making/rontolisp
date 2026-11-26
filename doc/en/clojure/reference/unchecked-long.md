# unchecked-long

`(unchecked-long x)`

Answers the low 64 bits of an integer or the truncation of a ratio as a signed integer, so `(unchecked-long 9223372036854775808)` is `-9223372036854775808`. A double saturates at the 64-bit range first (NaN is `0`). A character, `nil` and non-numbers signal. As a value a one-argument function.

```clojure
(println (unchecked-long 9223372036854775808) (unchecked-long 1e20)) ; -9223372036854775808 9223372036854775807
```
