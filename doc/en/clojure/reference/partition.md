# partition

`(partition n coll)` / `(partition n step coll)` / `(partition n step pad coll)`

Answers the groups of `n` over `coll`'s seq view, stepping by `step` (`n`
without one); an incomplete tail drops, like the oracle -- with a `pad`, it stays,
filled from the pad up to `n` (the pad may be endless). A lazy input answers a lazy
seq of strict groups, a strict one a strict list. A non-positive size signals. As a
value it takes two to four arguments.

```clojure
(println (partition 2 1 [1 2 3])) ; ((1 2) (2 3))
(println (partition 3 [1 2 3 4 5 6 7])) ; ((1 2 3) (4 5 6))
(println (take 2 (partition 2 (iterate inc 0)))) ; ((0 1) (2 3))
(println (partition 3 3 [:a :b] [1 2 3 4])) ; ((1 2 3) (4 :a :b))
```
