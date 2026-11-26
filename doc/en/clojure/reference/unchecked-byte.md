# unchecked-byte

`(unchecked-byte x)`

Answers the low 8 bits of an integer or the truncation of a ratio as a signed integer, so `(unchecked-byte 200)` is `-56`. A double saturates at the 32-bit range, then wraps. A character, `nil` and non-numbers signal. As a value a one-argument function.

```clojure
(println (unchecked-byte 200) (unchecked-byte -129)) ; -56 127
```
