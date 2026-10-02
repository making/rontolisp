# partition-by

`(partition-by f coll)` / `(partition-by f)`

Splits `coll`'s seq into runs where `(f x)` stays `=` to the run's first value. Each
run is strict; a lazy input answers a lazy seq of runs, a strict one a strict list.
`(partition-by f)` is its [transducer](transducers.md), stepping vectors. As a value one
or two arguments.

```clojure
(println (partition-by odd? [1 3 2 4 5])) ; ((1 3) (2 4) (5))
(println (take 2 (partition-by #(quot % 3) (iterate inc 0)))) ; ((0 1 2) (3 4 5))
(println (into [] (partition-by odd?) [1 3 2])) ; [[1 3] [2]]
```
