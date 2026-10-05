# nth

`(nth coll i)` / `(nth coll i default)`

Answers the `i`th element of `coll`: a vector or string indexes directly, a list or a
seq steps through it one element at a time, so a lazy input realizes only up to `i` and
an infinite one answers. Past either end the 3-arity answers `default` and the 2-arity
`nil`. A map, a set, a record and anything that is no collection signal
`UnsupportedOperationException`, like the oracle; a vector pattern destructures through
`nth`, so it refuses them too.

Deviation: `nth` past the end answers the default (nil without one), where the oracle
throws. As a VALUE `nth` is a `(collection index)` lambda -- the Clojure order -- since a
bare underlying `nth` takes the index first.

```clojure
(println (nth [10 20 30] 1))    ; 20
(println (nth [10 20] 5 :nf))   ; :nf
(println (map (fn [v] (nth v 0)) [[1 2] [3 4]])) ; (1 3)
```
