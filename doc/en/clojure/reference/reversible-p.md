# reversible?

`(reversible? x)`

`clojure.core/reversible?`: `true` for a vector (what `rseq` takes); a list is `false`. As a value a one-argument function.

```clojure
(println (reversible? [1]) (reversible? '(1)))  ; true false
```
