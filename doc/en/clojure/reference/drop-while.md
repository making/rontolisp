# drop-while

`(drop-while pred coll)` / `(drop-while pred)`

Answers `coll`'s seq view past the truthy prefix, sharing the tail: over a lazy `coll`
a lazy seq that steps over the prefix when it is first read. As a value a two-argument
lambda.

`(drop-while pred)` is its [transducer](transducers.md), as a value too.

```clojure
(println (drop-while neg? [-2 -1 0 1])) ; (0 1)
(println (into [] (drop-while neg?) [-2 0 1])) ; [0 1]
```
