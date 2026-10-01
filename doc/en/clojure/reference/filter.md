# filter

`(filter pred coll)`

Answers the list of the elements of `coll`'s seq view for which `pred` holds, in order;
the empty result is `nil`. As a value a lambda, so `filter` takes a bare predicate name.

```clojure
(println (filter odd? '(1 2 3 4)))  ; (1 3)
(println (filter first [[1] [] [2 3]])) ; ([1] [2 3])
```
