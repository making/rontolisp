# partitionv

`(partitionv n coll)` / `(partitionv n step coll)` / `(partitionv n step pad coll)`

`partition`'s runs as vectors, in order: an incomplete tail drops, or fills from `pad` up
to `n` members. A non-positive size or step signals (the oracle loops forever). A lazy input
answers a lazy seq, a strict one a strict list. As a value two to four arguments.

```clojure
(println (partitionv 2 [1 2 3 4])) ; ([1 2] [3 4])
(println (partitionv 2 [1 2 3])) ; ([1 2])
```
