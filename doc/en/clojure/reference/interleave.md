# interleave

`(interleave coll...)`

Answers one round-robin member at a time over every seq view, stopping at the
shortest, like the oracle. `(interleave)` is `nil`. As a value every argument's
seq view interleaved.

```clojure
(println (interleave [1 2] [:a :b])) ; (1 :a 2 :b)
```
