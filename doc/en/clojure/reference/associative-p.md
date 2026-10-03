# associative?

`(associative? x)`

`clojure.core/associative?`: `true` for a map, a record or a vector; a list and a set are `false`. As a value a one-argument function.

```clojure
(println (associative? [1]) (associative? {}) (associative? '(1)) (associative? #{1}))  ; true true false false
```
