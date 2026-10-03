# NaN?

`(NaN? x)`

`clojure.core/NaN?`: `true` for `##NaN`; any other number is `false`, and anything that is no number signals, like the oracle. As a value a one-argument function.

```clojure
(println (NaN? ##NaN) (NaN? 1))  ; true false
```
