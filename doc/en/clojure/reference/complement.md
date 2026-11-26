# complement

`(complement f)`

Answers the predicate negated, answering `T`-or-`false` like every
boolean-answering builtin. As a value a one-argument lambda over the same
negation.

```clojure
(println ((complement odd?) 4)) ; true
(println (map (complement odd?) [1 2])) ; (false true)
```
