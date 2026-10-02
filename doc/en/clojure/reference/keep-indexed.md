# keep-indexed

`(keep-indexed f coll)` / `(keep-indexed f)`

Like `keep`, but `f` takes the index and the item; the non-`nil` results answer
in order. Indexing starts at `0`, over the seq view. As a value a two-argument
lambda.

`(keep-indexed f)` is its [transducer](transducers.md), as a value too.

```clojure
(println (keep-indexed (fn [i x] (when (odd? x) i)) [10 11 12])) ; (1)
(println (into [] (keep-indexed (fn [i x] (when (odd? x) i))) [10 11 12])) ; [1]
```
