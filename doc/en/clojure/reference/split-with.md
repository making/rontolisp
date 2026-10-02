# split-with

`(split-with pred coll)`

Answers `[(take-while pred coll) (drop-while pred coll)]` in one walk over `coll`'s
seq; `false` stops the prefix like `nil`. An empty half is `nil`, where the oracle
prints `()`. As a value a two-argument function.

```clojure
(println (split-with odd? [1 3 4 5])) ; [(1 3) (4 5)]
```
