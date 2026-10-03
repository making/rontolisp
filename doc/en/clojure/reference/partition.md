# partition

`(partition n coll)` / `(partition n step coll)`

Answers the groups of `n` over `coll`'s seq view, stepping by `step` (`n`
without one); an incomplete tail drops, like the oracle. A lazy input answers a
lazy seq of strict groups, a strict one a strict list. A non-positive size
signals. A pad argument stays refused -- use two or three arguments, as a value
too.

```clojure
(println (partition 2 1 [1 2 3])) ; ((1 2) (2 3))
(println (partition 3 [1 2 3 4 5 6 7])) ; ((1 2 3) (4 5 6))
(println (take 2 (partition 2 (iterate inc 0)))) ; ((0 1) (2 3))
```
