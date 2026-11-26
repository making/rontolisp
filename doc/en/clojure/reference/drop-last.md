# drop-last

`(drop-last coll)` / `(drop-last n coll)`

Answers `coll`'s seq without its last `n` members (one without `n`). A lazy input
answers a lazy seq, so an infinite one works under `take`; a strict input answers a
strict list (`nil` when nothing is left, where the oracle prints `()`). As a value it
takes one or two arguments and signals the oracle's arity error otherwise.

```clojure
(println (drop-last [1 2 3])) ; (1 2)
(println (drop-last 2 [1 2 3])) ; (1)
(println (take 3 (drop-last (iterate inc 0)))) ; (0 1 2)
```
