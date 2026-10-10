# disj!

`(disj! tr)` `(disj! tr x & xs)`

Removes each member from the transient set `tr` in place and answers `tr`; of one
argument that argument. Any other transient is refused. As a value the same arities.

```clojure
(println (persistent! (disj! (transient #{1 2 3}) 1 2))) ; #{3}
```
