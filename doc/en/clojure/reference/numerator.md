# numerator

`(numerator x)`

Answers the numerator of a ratio in lowest terms (the sign sits here). An integer, a double or `nil` signals, like the oracle. As a value a one-argument function.

```clojure
(println (numerator 6/4) (numerator -3/6)) ; 3 -1
```
