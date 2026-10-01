# take

`(take n coll)`

Answers the list of the first `n` elements of `coll`'s seq view. Strict, no laziness; a
take longer than the collection answers the whole seq.

Deviation: an over-long take answers the whole seq as `nil` when it is empty, where the
oracle prints `()`.

```clojure
(println (take 3 (range 10))) ; (0 1 2)
(println (take 2 [1 2 3 4]))  ; (1 2)
(println (take 10 [1 2]))     ; (1 2)
```
