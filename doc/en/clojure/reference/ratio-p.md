# ratio?

`(ratio? x)`

`clojure.core/ratio?`: `true` for a ratio, a rational that is no integer. `1.5M` reads as `3/2` here, so it answers `true` (the oracle: `false`). As a value a one-argument function.

```clojure
(println (ratio? 1/2) (ratio? 2) (ratio? 0.5))  ; true false false
```
