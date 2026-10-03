# decimal?

`(decimal? x)`

`clojure.core/decimal?`: `false` for every value: an `M` literal reads as an exact rational (`1.5M` is `3/2`), so there is no decimal kind (the oracle answers `true` for `1.5M`). The argument is still evaluated. As a value a one-argument function.

```clojure
(println (decimal? 1.5M) (decimal? 1.5))  ; false false
```
