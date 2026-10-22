# realized?

`(realized? x)`

`clojure.core/realized?`: Whether the lazy seq, [delay](delay.md), [future](future.md) or [promise](promise.md) `x` has run its body (a delay whose body threw is realized, like the oracle's) or, for a promise, been delivered. A list (what a verb answers over a strict input) is realized, `true` where the oracle's unrealized lazy seq is `false`; an `iterate`, `cycle` or `repeat` seq is realized only once forced (the oracle: from the start). Anything else signals, like the oracle. As a value a one-argument function.

```clojure
(let [s (lazy-seq [1 2])]
  (println (realized? s) (first s) (realized? s)))  ; false 1 true
```
