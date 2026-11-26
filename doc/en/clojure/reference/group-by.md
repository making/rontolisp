# group-by

`(group-by f coll)`

Answers a fresh map from each function value to the vector of members answering
it, in encounter order. Of an empty collection, the empty map. As a value a
two-argument lambda.

```clojure
(println (group-by odd? [1 3])) ; {true [1 3]}
(println (group-by (fn [x] :same) [1 2])) ; {:same [1 2]}
```
