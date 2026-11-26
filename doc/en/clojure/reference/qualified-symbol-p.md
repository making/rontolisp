# qualified-symbol?

`(qualified-symbol? x)`

`clojure.core/qualified-symbol?`: `true` for a symbol with a namespace. As a value a one-argument function.

```clojure
(println (qualified-symbol? 'a/b) (qualified-symbol? 'a))  ; true false
```
