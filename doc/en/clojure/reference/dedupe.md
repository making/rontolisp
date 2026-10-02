# dedupe

`(dedupe coll)`

Answers `coll`'s seq without consecutive duplicates under `=` (so `1` and `1.0`
stay apart, and vectors and maps compare structurally). A lazy input answers a lazy
seq, a strict one a strict list. The zero-argument transducer arity is refused by
name. As a value a one-argument function.

```clojure
(println (dedupe [1 1 2 2 1 3 3])) ; (1 2 1 3)
(prn (dedupe [[1] [1] [2]])) ; ([1] [2])
```
