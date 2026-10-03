# keep

`(keep f coll)` / `(keep f)`

Answers the non-`nil` results of `f` over `coll`'s seq view, in order; `false` is
kept, only `nil` drops. A function that signals on a member signals, like the oracle
-- `(keep inc [1 nil 2])` throws instead of skipping. A lazy input answers a lazy
seq, a strict one a strict list. As a value a two-argument lambda.

`(keep f)` is its [transducer](transducers.md), as a value too.

```clojure
(println (keep inc [1 2 3])) ; (2 3 4)
(println (keep :k [{:k 1} {}])) ; (1)
(println (take 2 (keep #(when (odd? %) %) (iterate inc 0)))) ; (1 3)
(println (into [] (keep :k) [{:k 1} {}])) ; [1]
```
