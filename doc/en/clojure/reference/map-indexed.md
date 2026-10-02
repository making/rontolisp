# map-indexed

`(map-indexed f coll)` / `(map-indexed f)`

Answers `f` of the index and the item over `coll`'s seq view, strictly.
Indexing starts at `0`. As a value a two-argument lambda.

`(map-indexed f)` is its [transducer](transducers.md), as a value too.

```clojure
(println (map-indexed vector [:a :b])) ; ([0 :a] [1 :b])
(println (into [] (map-indexed vector) [:a])) ; [[0 :a]]
```
