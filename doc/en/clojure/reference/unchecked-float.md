# unchecked-float

`(unchecked-float x)`

Answers a number widened to a double: there is no single-precision value (see `float`). A value past the float range is the infinity of its sign, like the oracle. A character, `nil` and other non-numbers signal. As a value a one-argument function.

```clojure
(println (unchecked-float 1) (unchecked-float 1e300)) ; 1.0 ##Inf
```
