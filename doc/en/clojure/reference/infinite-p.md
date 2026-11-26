# infinite?

`(infinite? x)`

`clojure.core/infinite?`: `true` for `##Inf` and `##-Inf`; any other number is `false`, and anything that is no number signals, like the oracle. As a value a one-argument function.

```clojure
(println (infinite? ##Inf) (infinite? 1.5))  ; true false
```
