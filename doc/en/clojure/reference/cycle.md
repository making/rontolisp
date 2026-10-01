# cycle

`(cycle coll)`

Answers the seq view of `coll` cycled forever as a lazy seq; of an empty collection,
`nil`. Only terminates behind `take`.

```clojure
(println (take 5 (cycle [1 2]))) ; (1 2 1 2 1)
(println (take 10 (cycle [])))   ; nil
```
