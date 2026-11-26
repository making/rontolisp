# repeat

`(repeat x)` / `(repeat n x)`

Answers the infinite lazy seq of `x`, or (with a count) the strict list of `n` copies;
a non-positive count answers `nil`, like the oracle. The finite arity prints like the
oracle; the infinite one only terminates behind `take`.

```clojure
(println (take 5 (repeat 3))) ; (3 3 3 3 3)
(println (repeat 3 :x))       ; (:x :x :x)
```
