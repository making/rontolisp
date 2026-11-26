# neg-int?

`(neg-int? x)`

`clojure.core/neg-int?`: `true` for an `int?` below zero. As a value a one-argument function.

```clojure
(println (neg-int? -1) (neg-int? -1/2))  ; true false
```
