# remove

`(remove pred coll)` / `(remove pred)`

Answers the members of `coll`'s seq view the predicate rejects, in order -- the
complement of `filter`. A lazy input answers a lazy seq, a strict one a strict list.
As a value a two-argument lambda.

`(remove pred)` is its [transducer](transducers.md), as a value too.

```clojure
(println (remove odd? [1 2 3 4])) ; (2 4)
(println (take 3 (remove odd? (iterate inc 0)))) ; (0 2 4)
(println (into [] (remove odd?) [1 2 3])) ; [2]
```
