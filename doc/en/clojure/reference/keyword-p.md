# keyword?

`(keyword? x)`

`clojure.core/keyword?`: `true` for a keyword, qualified or not. As a value a one-argument function.

```clojure
(println (keyword? :a) (keyword? :a/b) (keyword? 'a))  ; true true false
```
