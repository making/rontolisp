# unchecked-int

`(unchecked-int x)`

Answers the low 32 bits of an integer or the truncation of a ratio as a signed integer, so `(unchecked-int 3000000000)` is `-1294967296`; a character is its code. A double saturates at the 32-bit range first (NaN is `0`). `nil` and non-numbers signal. As a value a one-argument function.

```clojure
(println (unchecked-int 3000000000) (unchecked-int 2.7) (unchecked-int \a)) ; -1294967296 2 97
```
