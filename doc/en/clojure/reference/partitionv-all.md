# partitionv-all

`(partitionv-all n coll)` / `(partitionv-all n step coll)`

`partition-all`'s runs as vectors, in order, the short tail kept. A non-positive size or
step signals (the oracle loops forever). A lazy input answers a lazy seq, a strict one a
strict list. As a value two or three arguments.

```clojure
(println (partitionv-all 2 [1 2 3])) ; ([1 2] [3])
```
