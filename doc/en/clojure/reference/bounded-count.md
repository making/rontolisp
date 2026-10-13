# bounded-count

`(bounded-count n coll)`

`coll`'s count when it is counted, whatever `n` is, else its members walked up to `n` -- so
a walk over an endless seq answers `n`, like the oracle's. A seq a lazy-or-strict verb
realized strictly counts whole, where the oracle's stays lazy and stops at `n`. As a value
two arguments.

```clojure
(println (bounded-count 5 [1 2 3]) (bounded-count 2 [1 2 3])) ; 3 3
```
