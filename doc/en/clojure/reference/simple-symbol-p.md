# simple-symbol?

`(simple-symbol? x)`

`clojure.core/simple-symbol?`: `true` for a symbol without a namespace (`/` alone is one). As a value a one-argument function.

```clojure
(println (simple-symbol? 'a) (simple-symbol? '/) (simple-symbol? 'a/b))  ; true true false
```
