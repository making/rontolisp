# realized?

`(realized? x)`

`clojure.core/realized?`: Whether the lazy seq `x` has run its body. A list (what a verb answers over a strict input) is realized, `true` where the oracle's unrealized lazy seq is `false`; an `iterate`, `cycle` or `repeat` seq is realized only once forced (the oracle: from the start). Anything else signals, like the oracle. As a value a one-argument function.

```clojure
(let [s (lazy-seq [1 2])]
  (println (realized? s) (first s) (realized? s)))  ; false 1 true
```
