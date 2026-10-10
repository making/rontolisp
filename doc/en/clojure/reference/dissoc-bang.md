# dissoc!

`(dissoc! tr k & ks)`

Removes each key from the transient map `tr` in place and answers `tr`; a key it does
not hold is ignored. Any other transient is refused. As a value a function of the same
arguments.

```clojure
(println (persistent! (dissoc! (transient {:a 1 :b 2}) :a :z))) ; {:b 2}
```
