# integer?

`(integer? x)`

`clojure.core/integer?`: `true` for an integer of any size. A `1M` literal reads as the integer `1` here, so it answers `true` (the oracle: a decimal, `false`). As a value a one-argument function.

```clojure
(println (integer? 1) (integer? 99999999999999999999) (integer? 1.0))  ; true true false
```
