# int?

`(int? x)`

`clojure.core/int?`: `true` for an integer a long holds (the oracle's `Long`, `Integer`, `Short`, `Byte`); a larger one is `false`. A `2N` literal reads as the integer `2` here, so it answers `true` (the oracle: a `BigInt`, `false`). As a value a one-argument function.

```clojure
(println (int? 1) (int? 9223372036854775808))  ; true false
```
