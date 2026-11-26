# record?

`(record? x)`

`clojure.core/record?`: `true` for a record; a plain map, a deftype value and everything else are `false`. As a value a one-argument function.

```clojure
(defrecord RpR [a])
(println (record? (->RpR 1)) (record? {:a 1}))  ; true false
```
