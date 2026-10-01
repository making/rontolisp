# distinct

`(distinct coll)`

Answers `coll`'s seq view with later duplicates dropped, first occurrences kept
in order. Membership is `equal` (vectors key by identity, like the table
runtime). As a value a one-argument lambda.

```clojure
(println (distinct [3 1 3 2 1])) ; (3 1 2)
```
