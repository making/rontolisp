# filter

`(filter pred coll)`

Answers the elements of `coll`'s seq view for which `pred` holds, in order; the empty
result is `nil`. When `coll` is lazy the answer is a lazy seq, skipping non-matching
elements as it realizes. As a value a lambda, so `filter` takes a bare predicate name.

```clojure
(println (filter odd? '(1 2 3 4)))  ; (1 3)
(println (filter first [[1] [] [2 3]])) ; ([1] [2 3])
(println (take 4 (filter odd? (iterate inc 0)))) ; (1 3 5 7)
```
