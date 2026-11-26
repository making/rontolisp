# simple-ident?

`(simple-ident? x)`

`clojure.core/simple-ident?`: `true` for a keyword or symbol without a namespace. As a value a one-argument function.

```clojure
(println (simple-ident? :a) (simple-ident? 'a/b))  ; true false
```
