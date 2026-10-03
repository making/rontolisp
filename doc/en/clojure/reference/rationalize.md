# rationalize

`(rationalize x)`

Answers `nil` as `nil`, a rational itself, and a double as the exact rational of the shortest decimal it prints as, so `(rationalize 0.1)` is `1/10` and `(rationalize 0.3333333333333333)` is `3333333333333333/10000000000000000`, like the oracle. NaN and the infinities signal, so does a non-number. As a value a one-argument function.

```clojure
(println (rationalize 0.5) (rationalize 0.1) (rationalize nil)) ; 1/2 1/10 nil
```
