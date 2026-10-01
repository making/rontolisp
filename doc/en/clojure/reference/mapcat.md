# mapcat

`(mapcat f coll...)`

Maps `f` over the collections and concats the mapped seq views, strictly
(nil-safe: a `nil` result contributes nothing, like `concat`). A lone function is
the oracle's transducer shape and stays refused. As a value a rest lambda.

```clojure
(println (mapcat reverse [[1 2] [3 4]])) ; (2 1 4 3)
(println (mapcat vals [{:a 1} {:b 2}])) ; (1 2)
```
