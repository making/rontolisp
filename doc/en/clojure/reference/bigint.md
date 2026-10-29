# bigint

`(bigint x)`

Answers a number truncated toward zero to an integer (a decimal too), or a decimal string parsed. NaN, an infinity, `nil`, a character or a malformed string signal, like the oracle. Every integer is arbitrary precision: one past the long range prints with the oracle's `N`, one inside it is a plain integer and prints without (the oracle's `BigInt` prints `1N`). As a value a one-argument function.

```clojure
(println (bigint 1.5) (bigint 7/2) (bigint "123") (bigint 1e20)) ; 1 3 123 100000000000000000000N
```
