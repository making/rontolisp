# empty?

`(empty? coll)`

Answers whether `coll` is empty, table-, vector- and string-aware: `nil`, an empty map,
set, vector or string are empty, and a lazy seq when it realizes to nothing (one element
realizes). Anything that is no collection (a keyword, `false`, a number) signals as `seq`
does, `IllegalArgumentException` like the oracle. Answers `T`-or-false.

```clojure
(println (empty? '()))    ; true
(println (empty? ""))     ; true
(println (empty? {}))     ; true
(println (empty? #{}))    ; true
(println (empty? [1]))    ; false
```
