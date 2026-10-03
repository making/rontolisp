# unchecked-add

`(unchecked-add a b)`

Answers the sum of integers wrapped to 64 bits, so `(unchecked-add 9223372036854775807 1)` is `-9223372036854775808`. A double or a ratio takes the plain result. `nil` and non-numbers signal. As a value a two-argument function.

An integer past 64 bits is a plain integer here, so the oracle's unwrapped bigint (`100000000000000000000N`) wraps too.

```clojure
(println (unchecked-add 9223372036854775807 1)) ; -9223372036854775808
```
