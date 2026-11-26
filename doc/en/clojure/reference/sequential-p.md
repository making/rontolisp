# sequential?

`(sequential? x)`

`clojure.core/sequential?`: `true` for a list, a lazy seq or a vector; `false` for a map, set, string and `nil`. As a value a one-argument function.

```clojure
(println (sequential? [1]) (sequential? '(1)))  ; true true
(println (sequential? #{1}) (sequential? "ab")) ; false false
```
