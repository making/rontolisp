# key

`(key e)`

`clojure.core/key`: the key of the map entry `e`, an entry being what `first` of a map,
`seq` of a map or `find` answers (a plain two-member vector here). Anything that is no
two-member vector (a map, a list, `nil`, a number) signals, like the oracle. As a value a
one-argument function.

```clojure
(println (key (first {:a 1})))     ; :a
(println (map key {:a 1 :b 2}))    ; (:a :b)
```
