# conj!

`(conj!)` `(conj! coll)` `(conj! tr x)`

Adds `x` to the transient `tr` in place and answers `tr`: at a vector's end, as a set's
member, as a map's entries (what [`conj`](conj.md) takes; a vector that is no pair is the
oracle's `IllegalArgumentException`). With no argument a fresh transient vector, with one
that argument. As a value the same three arities.

```clojure
(println (persistent! (conj! (transient {}) [:a 1]))) ; {:a 1}
(println (persistent! (reduce conj! (transient []) (range 3)))) ; [0 1 2]
(println (persistent! (conj!))) ; []
```
