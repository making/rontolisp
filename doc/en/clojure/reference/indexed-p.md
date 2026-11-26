# indexed?

`(indexed? x)`

`clojure.core/indexed?`: `true` for a vector; a list and a string are `false`. As a value a one-argument function.

```clojure
(println (indexed? [1]) (indexed? '(1)) (indexed? "ab"))  ; true false false
```
