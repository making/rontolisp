# map?

`(map? x)`

`clojure.core/map?`: `true` for a map (a sorted one too) or a record. As a value a one-argument function.

```clojure
(defrecord MpR [a])
(println (map? {:a 1}) (map? (->MpR 1)) (map? [[:a 1]]))  ; true true false
```
