# reduce-kv

`(reduce-kv f init coll)`

Folds `(f acc k v)` over a map's or record's entries (the table's walk order) or a
vector's index/member pairs, starting from `init`; `nil` answers `init`. A list, set
or string signals, like the oracle. `reduced` is not supported. As a value a
three-argument function.

```clojure
(println (reduce-kv (fn [acc k v] (+ acc v)) 0 {:a 1 :b 2})) ; 3
(println (reduce-kv (fn [acc i x] (conj acc [i x])) [] [:x :y])) ; [[0 :x] [1 :y]]
```
