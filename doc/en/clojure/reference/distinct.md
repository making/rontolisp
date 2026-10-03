# distinct

`(distinct coll)` / `(distinct)`

Answers `coll`'s seq view with later duplicates dropped, first occurrences kept in
order. Membership is `=`, like a set's. A lazy input answers a lazy seq, a strict
one a strict list. As a value a one-argument lambda.

`(distinct)` is its [transducer](transducers.md), as a value too.

```clojure
(println (distinct [3 1 3 2 1])) ; (3 1 2)
(println (take 3 (distinct (cycle [1 2 1 3])))) ; (1 2 3)
(println (into [] (distinct) [3 1 3])) ; [3 1]
```
