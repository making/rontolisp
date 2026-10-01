# some

`(some pred coll)`

Answers the first truthy `(pred x)` over `coll`'s seq view, or `nil` -- the
predicate's own value, not the member. As a value a two-argument lambda.

```clojure
(println (some even? [1 3 4])) ; true
(println (some even? [1 3])) ; nil
```
