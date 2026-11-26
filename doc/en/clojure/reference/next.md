# next

`(next coll)`

Answers the seq view of `coll` without its first element, `nil` when that view is empty
-- `(next coll)` is `(seq (rest coll))`, so a lazy seq with nothing past its first
element answers `nil` too, where `rest` answers its empty tail `()`.

```clojure
(println (next '(1 2 3))) ; (2 3)
(println (next [1]))      ; nil
(println (next []))       ; nil
```
