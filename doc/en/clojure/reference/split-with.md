# split-with

`(split-with pred coll)`

Answers `[(take-while pred coll) (drop-while pred coll)]`, like the oracle's: each half
lazy over a lazy `coll`, `pred` running on the prefix once per half; `false` stops the
prefix like `nil`. An empty half of a strict collection is `nil`, where the oracle
prints `()`. As a value a two-argument function.

```clojure
(println (split-with odd? [1 3 4 5])) ; [(1 3) (4 5)]
```
