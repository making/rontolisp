# splitv-at

`(splitv-at n coll)`

`split-at` with the taken part as a vector: `[(vec (take n coll)) (drop n coll)]`. As a
value two arguments.

```clojure
(println (splitv-at 2 [1 2 3 4])) ; [[1 2] (3 4)]
```
