# pop!

`(pop! tr)`

Removes the last member of the transient vector `tr` in place and answers `tr`; an empty
one is the oracle's `IllegalStateException` (`Can't pop empty vector`). Any other
transient is refused. As a value a one-argument function.

```clojure
(println (persistent! (pop! (transient [1 2 3])))) ; [1 2]
```
