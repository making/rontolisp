# unchecked-dec

`(unchecked-dec x)`

Answers `x` minus one of integers wrapped to 64 bits, so `(unchecked-dec -9223372036854775808)` is `9223372036854775807`. A double or a ratio takes the plain result. `nil` and non-numbers signal. As a value a one-argument function.

An integer past 64 bits is a plain integer here, so the oracle's unwrapped bigint (`100000000000000000000N`) wraps too.

```clojure
(println (unchecked-dec -9223372036854775808)) ; 9223372036854775807
```
