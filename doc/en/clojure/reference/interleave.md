# interleave

`(interleave coll...)`

Answers one round-robin member at a time over every seq view, stopping at the
shortest, like the oracle: a lazy seq when any input is lazy, a strict list
otherwise. `(interleave)` is `nil`. As a value every argument interleaved.

```clojure
(println (interleave [1 2] [:a :b])) ; (1 :a 2 :b)
(println (take 4 (interleave (iterate inc 0) (repeat :x)))) ; (0 :x 1 :x)
```
