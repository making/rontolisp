# take-while

`(take-while pred coll)` / `(take-while pred)`

Answers the strict prefix of `coll`'s seq view while `pred` stays truthy
(`false` stops, like `nil`). As a value a two-argument lambda.

`(take-while pred)` is its [transducer](transducers.md), as a value too.

```clojure
(println (take-while pos? [3 1 -1 5])) ; (3 1)
(println (into [] (take-while pos?) [3 1 -1 5])) ; [3 1]
```
