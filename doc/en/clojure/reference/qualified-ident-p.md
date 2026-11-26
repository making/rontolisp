# qualified-ident?

`(qualified-ident? x)`

`clojure.core/qualified-ident?`: `true` for a keyword or symbol with a namespace. As a value a one-argument function.

```clojure
(println (qualified-ident? :a/b) (qualified-ident? 'a))  ; true false
```
