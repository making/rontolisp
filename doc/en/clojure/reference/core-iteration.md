# iteration

`(iteration step & {:keys [somef vf kf initk]})`

`clojure.core/iteration`: a value that is seqable and reducible over repeated calls of
`step`, a function of a continuation token. The first call takes `initk` (default `nil`)
and answers `ret`; while `(somef ret)` is truthy (default `some?`), `(vf ret)` (default
`identity`) is the next element and `(kf ret)` (default `identity`) the next token,
which ends the iteration when `nil`. The options are keyword arguments or one map.

`seq` (and `first`, `map`, `take` ...) calls `step` again from `initk` each time and is
lazy: an element's `somef`, `vf` and `kf` run when it is reached, the next `step` when the
rest is realized. `reduce` with an init, `into`, `vec`, `transduce` and `mapv` reduce it
directly, stopping at `reduced`. Like the oracle, `reduce` without an init is a
`ClassCastException` and `count` an `UnsupportedOperationException`. As a value one or
more arguments.

```clojure
(def pages (iteration (fn [k] (when (< k 3) {:page k :items [k k]}))
                      :initk 0 :kf (comp inc :page) :vf :items))
(println (vec pages))           ; [[0 0] [1 1] [2 2]]
(println (mapcat identity pages)) ; (0 0 1 1 2 2)
(println (take 3 (iteration inc :initk 0))) ; (1 2 3)
```
