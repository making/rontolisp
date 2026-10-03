# counted?

`(counted? x)`

`clojure.core/counted?`: `true` for a list, vector, map, set (sorted ones too) or record; a lazy seq, a string and `nil` are `false`. A seq a verb answers over a strict input is a list, so it counts here where the oracle's lazy seq does not. As a value a one-argument function.

```clojure
(println (counted? [1]) (counted? (lazy-seq [1])) (counted? "ab"))  ; true false false
```
