# vec

`(vec coll)`

Answers a vector over the collection's members: lists pass through member by member,
strings become character vectors, maps contribute one two-vector per entry, sets one
member per element. `(vec nil)` is `[]`. Lazy inputs realize fully (an infinite input
hangs, like the oracle's). A value that is no collection (a number, keyword, symbol,
function, matcher ...) signals `RuntimeException`, as the oracle's does.

As a value a one-argument lambda.

```clojure
(println (vec '(1 2)))      ; [1 2]
(println (vec "ab"))        ; [a b]
(println (vec nil))         ; []
(println (vec (map inc [1 2]))) ; [2 3]
(println (map vec (list [1] '(2)))) ; ([1] [2])
```
