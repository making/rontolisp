# take

`(take n coll)` / `(take n)`

Answers the list of the first `n` elements of `coll`, stepping through one lazy
element at a time, so `(take n infinite)` terminates with a strict prefix. Realizes
exactly what it answers: the next element stays unrealized when the count runs out.

Deviation: an over-long take answers the whole seq as `nil` when it is empty, where the
oracle prints `()`.

`(take n)` is its [transducer](transducers.md), as a value too.

```clojure
(println (take 3 (range 10))) ; (0 1 2)
(println (take 2 [1 2 3 4]))  ; (1 2)
(println (take 10 [1 2]))     ; (1 2)
(println (take 5 (iterate inc 0))) ; (0 1 2 3 4)
(println (into [] (take 2) (iterate inc 0))) ; [0 1]
```
