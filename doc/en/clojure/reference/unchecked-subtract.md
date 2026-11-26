# unchecked-subtract

`(unchecked-subtract a b)`

Answers the difference of integers wrapped to 64 bits, so `(unchecked-subtract -9223372036854775808 1)` is `9223372036854775807`. A double or a ratio takes the plain result. `nil` and non-numbers signal. As a value a two-argument function.

An integer past 64 bits is a plain integer here, so the oracle's unwrapped bigint (`100000000000000000000N`) wraps too.

```clojure
(println (unchecked-subtract -9223372036854775808 1)) ; 9223372036854775807
```
