# eduction

`(eduction xform* coll)`

Answers `coll` stepped through the transducers, composed like `comp`. As a value
transducers then one collection.

Deviation: the eduction is that seq, computed once -- strictly over a strict input,
lazily over a lazy one -- where the oracle re-runs the transformation every time it is
reduced; `println` prints it as the seq, where the oracle prints the object (`prn`
agrees).

```clojure
(prn (eduction (filter odd?) (range 6))) ; (1 3 5)
(prn (eduction (map inc) (filter even?) [1 2 3 4])) ; (2 4)
(println (reduce + 0 (eduction (map inc) [1 2 3]))) ; 9
```
