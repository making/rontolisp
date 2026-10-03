# simple-keyword?

`(simple-keyword? x)`

`clojure.core/simple-keyword?`: `true` for a keyword without a namespace. As a value a one-argument function.

```clojure
(println (simple-keyword? :a) (simple-keyword? :a/b) (simple-keyword? 'a))  ; true false false
```
