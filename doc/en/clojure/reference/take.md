# take

`(take n coll)` / `(take n)`

Answers the first `n` elements of `coll`: over a lazy `coll` a lazy seq realizing one
element per element read, so `(take n infinite)` terminates and an element never read
is never realized; over a strict `coll` the strict list.

Deviation: over a strict collection an empty take answers `nil`, where the oracle
prints `()`.

`(take n)` is its [transducer](transducers.md), as a value too.

```clojure
(println (take 3 (range 10))) ; (0 1 2)
(println (take 2 [1 2 3 4]))  ; (1 2)
(println (take 10 [1 2]))     ; (1 2)
(println (take 5 (iterate inc 0))) ; (0 1 2 3 4)
(println (into [] (take 2) (iterate inc 0))) ; [0 1]
```
