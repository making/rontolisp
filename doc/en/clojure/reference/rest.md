# rest

`(rest coll)`

Answers the seq view of `coll` without its first element. `rest` of empty is `nil` and
never signals, matching the oracle's total `rest`.

Deviation: `rest` of an empty collection answers `nil`, where the oracle prints `()`.

```clojure
(println (rest '(1 2 3))) ; (2 3)
(println (rest [1 2 3]))  ; (2 3)
(println (rest []))       ; nil
```
