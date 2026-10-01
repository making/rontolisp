# map-indexed

`(map-indexed f coll)`

Answers `f` of the index and the item over `coll`'s seq view, strictly.
Indexing starts at `0`. As a value a two-argument lambda.

```clojure
(println (map-indexed vector [:a :b])) ; ([0 :a] [1 :b])
```
