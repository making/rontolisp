# sorted?

`(sorted? x)`

`clojure.core/sorted?`: `true` for a sorted map or set (`sorted-map`, `sorted-set` and their `-by` forms); a hash map, a set and a vector are `false`. As a value a one-argument function.

```clojure
(println (sorted? (sorted-map :a 1)) (sorted? {:a 1}) (sorted? [1 2]))  ; true false false
```
