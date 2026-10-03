# empty?

`(empty? coll)`

Answers whether `coll` is empty, table-, vector- and string-aware: `nil`, the false
object, an empty map, set, vector or string are empty, and a lazy seq when it realizes
to nothing (one element realizes). Answers `T`-or-false.

Deviation: `(empty? false)` answers `true` here, where the oracle signals.

```clojure
(println (empty? '()))    ; true
(println (empty? ""))     ; true
(println (empty? {}))     ; true
(println (empty? #{}))    ; true
(println (empty? [1]))    ; false
```
