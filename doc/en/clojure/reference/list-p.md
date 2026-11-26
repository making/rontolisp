# list?

`(list? x)`

`clojure.core/list?`: `true` for a list. A seq a verb answers over a strict input (`map`, `filter`, `range`, `seq` of a vector, ...) is a list here, so it answers `true` where the oracle's lazy or chunked seq answers `false`; a lazy seq is `false` in both. `(list? ())` is `false` (`nil` is the empty list). As a value a one-argument function.

```clojure
(println (list? '(1)) (list? [1]) (list? (lazy-seq [1])))  ; true false false
```
