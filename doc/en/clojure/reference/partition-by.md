# partition-by

`(partition-by f coll)`

Splits `coll`'s seq into runs where `(f x)` stays `=` to the run's first value. Each
run is strict; a lazy input answers a lazy seq of runs, a strict one a strict list.
The one-argument transducer arity is refused by name. As a value a two-argument
function.

```clojure
(println (partition-by odd? [1 3 2 4 5])) ; ((1 3) (2 4) (5))
(println (take 2 (partition-by #(quot % 3) (iterate inc 0)))) ; ((0 1 2) (3 4 5))
```
