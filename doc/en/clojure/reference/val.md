# val

`(val e)`

`clojure.core/val`: the value of the map entry `e`; see `key` for what counts as an entry
and what signals. As a value a one-argument function.

```clojure
(println (val (first {:a 1})))     ; 1
(println (map val {:a 1 :b 2}))    ; (1 2)
```
