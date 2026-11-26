# unchecked-multiply

`(unchecked-multiply a b)`

Answers the product of integers wrapped to 64 bits, so `(unchecked-multiply 4611686018427387904 4)` is `0`. A double or a ratio takes the plain result. `nil` and non-numbers signal. As a value a two-argument function.

An integer past 64 bits is a plain integer here, so the oracle's unwrapped bigint (`100000000000000000000N`) wraps too.

```clojure
(println (unchecked-multiply 4611686018427387904 4)) ; 0
```
