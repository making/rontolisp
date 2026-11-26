# float

`(float x)`

Answers a number widened to a double: there is no single-precision value, so `(float 1/3)` is
`0.3333333333333333` where the oracle's is `0.33333334`. A value past the float range (and an
infinity) signals `Value out of range for float: ...`, like the oracle; a non-number signals. As a
value a one-argument function.

```clojure
(println (float 1) (float 1/2)) ; 1.0 0.5
```
