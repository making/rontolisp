# sequence

`(sequence coll)` / `(sequence xform coll...)`

Of one collection, its seq (a lazy seq itself). With a [transducer](transducers.md),
the collections step through it one element at a time: several step in lockstep to the
shortest, each row spread as the step's arguments (only `map` takes several). A lazy
input answers a lazy seq, so `take` of an infinite one terminates; a strict input
answers a strict list. The empty result is `nil`, where the oracle prints `()`. As a
value one or more arguments.

```clojure
(println (sequence (map inc) [1 2 3])) ; (2 3 4)
(println (take 3 (sequence (map inc) (iterate inc 0)))) ; (1 2 3)
(println (sequence (map vector) [1 2] [3 4])) ; ([1 3] [2 4])
```
