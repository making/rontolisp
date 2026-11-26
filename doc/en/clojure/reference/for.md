# for

`(for [binding...] expr)`

Answers `expr` over every combination of the bindings, left to right. Pairs, patterns
and modifiers work like `doseq`'s: each `:when` skips its element, each `:while` ends its
level, each `:let` binds sequentially. While every collection it steps over is strict the
answer is realized at once, a strict list; from the first lazy collection on (the first
binding's included) the rest is a lazy seq, realized as it is consumed, so `first` and
`take` realize only what they answer and an infinite collection ends behind them.

Deviation: an empty strict result is `nil`, where the oracle prints `()`; a strict answer
realizes when the `for` runs, where the oracle's waits to be consumed.

```clojure
(println (for [x [1 2 3] :when (odd? x)] (* x 10))) ; (10 30)
(println (for [x [1 2] y [3 4]] [x y])) ; ([1 3] [1 4] [2 3] [2 4])
(println (for [x [1 2 3 4] :while (< x 3)] x)) ; (1 2)
```
