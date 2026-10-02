# interpose

`(interpose sep coll)` / `(interpose sep)`

Answers the members of `coll`'s seq view with `sep` between every two, strictly.
A one-member collection never shows the separator. As a value a two-argument
lambda.

`(interpose sep)` is its [transducer](transducers.md), as a value too.

```clojure
(println (interpose 0 [1 2 3])) ; (1 0 2 0 3)
(println (into [] (interpose 0) [1 2 3])) ; [1 0 2 0 3]
```
