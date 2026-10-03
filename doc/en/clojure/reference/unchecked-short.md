# unchecked-short

`(unchecked-short x)`

Answers the low 16 bits of an integer or the truncation of a ratio as a signed integer, so `(unchecked-short 70000)` is `4464`. A double saturates at the 32-bit range, then wraps. A character, `nil` and non-numbers signal. As a value a one-argument function.

```clojure
(println (unchecked-short 70000) (unchecked-short 32768)) ; 4464 -32768
```
