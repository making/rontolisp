# partition

`(partition n coll)` / `(partition n step coll)`

Answers the strict groups of `n` over `coll`'s seq view, stepping by `step`
(`n` without one); an incomplete tail drops, like the oracle. A non-positive
size signals. A pad argument stays refused -- use two or three arguments. As a
value a one- or two-argument lambda.

```clojure
(println (partition 2 1 [1 2 3])) ; ((1 2) (2 3))
(println (partition 3 [1 2 3 4 5 6 7])) ; ((1 2 3) (4 5 6))
```
