# ident?

`(ident? x)`

`clojure.core/ident?`: `true` for a keyword or a symbol. As a value a one-argument function.

```clojure
(println (ident? :a) (ident? 'a) (ident? "a"))  ; true true false
```
