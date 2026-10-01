# next

`(next coll)`

Answers the seq view of `coll` without its first element, `nil` when that view is empty
-- `(next coll)` is `(seq (rest coll))`.

Deviation: `next` of a one-element collection answers `nil`, where the oracle prints
`()`.

```clojure
(println (next '(1 2 3))) ; (2 3)
(println (next [1]))      ; nil
(println (next []))       ; nil
```
