# keep

`(keep f coll)`

Answers the non-`nil` results of `f` over `coll`'s seq view, in order; `false` is
kept, only `nil` drops. A function that signals on a member signals, like the
oracle -- `(keep inc [1 nil 2])` throws instead of skipping. As a value a
two-argument lambda.

```clojure
(println (keep inc [1 2 3])) ; (2 3 4)
(println (keep :k [{:k 1} {}])) ; (1)
```
