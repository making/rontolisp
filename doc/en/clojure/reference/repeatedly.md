# repeatedly

`(repeatedly f)` / `(repeatedly n f)`

Answers the infinite lazy seq of `(f)` calls, or (with a count) the strict list of `n`
calls; a non-positive count answers `nil`. The finite arity prints like the oracle; the
infinite one only terminates behind `take`.

```clojure
(println (take 3 (repeatedly (fn [] 7)))) ; (7 7 7)
(println (repeatedly 2 (fn [] 7)))        ; (7 7)
```
