# assoc!

`(assoc! tr k v & kvs)`

Sets each key of the transient map, or each index of the transient vector, in place and
answers `tr`; a missing last value is `nil`, like the oracle's. A vector index equal to
the count appends; any other out of range is the oracle's `IndexOutOfBoundsException`,
a key that is no integer its `IllegalArgumentException`. A transient set is refused. As a
value a function of the same arguments.

```clojure
(println (persistent! (assoc! (transient {:a 1}) :b 2 :c 3))) ; {:a 1, :b 2, :c 3}
(println (persistent! (assoc! (transient [1 2]) 0 :x 2 :y))) ; [:x 2 :y]
```
