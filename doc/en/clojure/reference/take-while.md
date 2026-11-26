# take-while

`(take-while pred coll)` / `(take-while pred)`

Answers the elements of `coll`'s seq view while `pred` stays truthy (`false` stops,
like `nil`): over a lazy `coll` a lazy seq testing one element per element read, so an
infinite `coll` whose `pred` never fails still answers its first elements; over a
strict `coll` the strict list. As a value a two-argument lambda.

`(take-while pred)` is its [transducer](transducers.md), as a value too.

```clojure
(println (take-while pos? [3 1 -1 5])) ; (3 1)
(println (take 3 (take-while pos? (iterate inc 1)))) ; (1 2 3)
(println (into [] (take-while pos?) [3 1 -1 5])) ; [3 1]
```
