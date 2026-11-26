# map-indexed

`(map-indexed f coll)` / `(map-indexed f)`

Answers `f` of the index and the item over `coll`'s seq view. Indexing starts at
`0`. A lazy input answers a lazy seq, a strict one a strict list. As a value a
two-argument lambda.

`(map-indexed f)` is its [transducer](transducers.md), as a value too.

```clojure
(println (map-indexed vector [:a :b])) ; ([0 :a] [1 :b])
(println (take 2 (map-indexed vector (iterate inc 10)))) ; ([0 10] [1 11])
(println (into [] (map-indexed vector) [:a])) ; [[0 :a]]
```
