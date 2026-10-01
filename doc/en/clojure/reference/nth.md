# nth

`(nth coll i)` / `(nth coll i default)`

Answers the `i`th element of `coll`'s seq view. Past the end the 3-arity answers
`default` and the 2-arity `nil`.

Deviation: `nth` past the end answers the default (nil without one), where the oracle
throws. As a VALUE `nth` is a `(collection index)` lambda -- the Clojure order -- since a
bare underlying `nth` takes the index first.

```clojure
(println (nth [10 20 30] 1))    ; 20
(println (nth [10 20] 5 :nf))   ; :nf
(println (map (fn [v] (nth v 0)) [[1 2] [3 4]])) ; (1 3)
```
