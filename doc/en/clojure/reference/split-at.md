# split-at

`(split-at n coll)`

Answers `[(take n coll) (drop n coll)]`: the two halves of `coll`'s seq at `n`.
An empty half is `nil`, where the oracle prints `()`. As a value a two-argument
function.

```clojure
(println (split-at 2 [1 2 3 4])) ; [(1 2) (3 4)]
(println (split-at 5 [1 2])) ; [(1 2) nil]
```
