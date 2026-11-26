# reversible?

`(reversible? x)`

`clojure.core/reversible?`: `true` for a vector, a sorted map or a sorted set (what `rseq` takes); a list and a hash map are `false`. As a value a one-argument function.

```clojure
(println (reversible? [1]) (reversible? (sorted-set 1)) (reversible? '(1)))  ; true true false
```
