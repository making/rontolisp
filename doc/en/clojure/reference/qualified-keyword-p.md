# qualified-keyword?

`(qualified-keyword? x)`

`clojure.core/qualified-keyword?`: `true` for a keyword with a namespace, `::kw` included. As a value a one-argument function.

```clojure
(println (qualified-keyword? :a/b) (qualified-keyword? ::k) (qualified-keyword? :a))  ; true true false
```
