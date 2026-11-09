# mapcat

`(mapcat f coll...)` / `(mapcat f)`

Maps `f` over the collections and concats the mapped seq views (nil-safe: a `nil`
result contributes nothing, like `concat`): lazily when a collection is lazy, each
mapped seq read as it is reached, else strictly unless a mapped seq is lazy. Over a
lazy input only what is read realizes, where the oracle realizes up to four mapped
elements ahead. `(mapcat f)` is its
[transducer](transducers.md), `(comp (map f) cat)`. As a value a rest lambda, which
answers the transducer of the function alone.

```clojure
(println (mapcat reverse [[1 2] [3 4]])) ; (2 1 4 3)
(println (mapcat vals [{:a 1} {:b 2}])) ; (1 2)
(println (into [] (mapcat (fn [x] [x x])) [1 2])) ; [1 1 2 2]
```
