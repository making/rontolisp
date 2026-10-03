# seq?

`(seq? x)`

`clojure.core/seq?`: `true` for a list or a lazy seq; `false` for a vector, map, set, string and `nil`. `nil` is the empty list here, so `(seq? ())` is `false` where the oracle answers `true`. A seq a verb answers over a strict input is a list. As a value a one-argument function.

```clojure
(println (seq? '(1 2)) (seq? (map inc [1])))  ; true true
(println (seq? [1 2]) (seq? nil))           ; false false
```
