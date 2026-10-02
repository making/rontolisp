# drop

`(drop n coll)` / `(drop n)`

Answers `coll` without its first `n` elements, stepping through one lazy element at a
time; the remainder may stay lazy when the input is lazy.

Deviation: an over-long drop answers `nil`, where the oracle prints `()`.

`(drop n)` is its [transducer](transducers.md), as a value too.

```clojure
(println (drop 2 [1 2 3 4]))  ; (3 4)
(println (drop 8 (range 10))) ; (8 9)
(println (drop 10 [1 2]))     ; nil
(println (take 3 (drop 2 (iterate inc 0)))) ; (2 3 4)
(println (into [] (drop 1) [1 2 3])) ; [2 3]
```
