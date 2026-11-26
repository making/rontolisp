# take-last

`(take-last n coll)`

Answers the last `n` members of `coll`'s seq as a strict list, `nil` when `n` is not
positive or `coll` is empty. The whole input is realized, like the oracle's. As a
value a two-argument function.

```clojure
(println (take-last 2 [1 2 3])) ; (2 3)
(println (take-last 0 [1 2 3])) ; nil
```
