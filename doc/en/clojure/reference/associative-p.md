# associative?

`(associative? x)`

`clojure.core/associative?`: `true` for a map (a sorted one too), a record or a vector; a list and a set (a sorted one too) are `false`. As a value a one-argument function.

```clojure
(println (associative? [1]) (associative? {}) (associative? '(1)) (associative? #{1}))  ; true true false false
```
