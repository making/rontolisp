# remove

`(remove pred coll)` / `(remove pred)`

Answers the members of `coll`'s seq view the predicate rejects, in order -- the
complement of `filter`. As a value a two-argument lambda.

`(remove pred)` is its [transducer](transducers.md), as a value too.

```clojure
(println (remove odd? [1 2 3 4])) ; (2 4)
(println (into [] (remove odd?) [1 2 3])) ; [2]
```
