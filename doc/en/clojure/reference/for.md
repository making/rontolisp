# for

`(for [binding...] expr)`

Answers the strict list of `expr` over every combination of the bindings, left to right.
Pairs, patterns and modifiers work like `doseq`'s: each `:when` skips its element, each
`:while` ends its level's loop, each `:let` binds sequentially.

Deviation: an empty result is `nil`, where the oracle prints `()`.

```clojure
(println (for [x [1 2 3] :when (odd? x)] (* x 10))) ; (10 30)
(println (for [x [1 2] y [3 4]] [x y])) ; ([1 3] [1 4] [2 3] [2 4])
(println (for [x [1 2 3 4] :while (< x 3)] x)) ; (1 2)
```
