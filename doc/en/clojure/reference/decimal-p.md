# decimal?

`(decimal? x)`

`clojure.core/decimal?`: `true` for a decimal -- an `M` literal, a `bigdec`, a read of one, the oracle's `BigDecimal` -- and `false` for anything else, a double and a ratio included. As a value a one-argument function.

```clojure
(println (decimal? 1.5M) (decimal? (bigdec "2")) (decimal? 1.5) (decimal? 3/2))  ; true true false false
```
