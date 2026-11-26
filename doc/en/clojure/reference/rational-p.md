# rational?

`(rational? x)`

`clojure.core/rational?`: `true` for an integer or a ratio (and an `M` literal, an exact rational here, as in the oracle); a double is `false`. As a value a one-argument function.

```clojure
(println (rational? 1) (rational? 1/2) (rational? 0.5))  ; true true false
```
