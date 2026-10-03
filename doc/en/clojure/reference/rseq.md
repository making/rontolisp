# rseq

`(rseq v)`

`clojure.core/rseq`: the members of the vector `v`, last first, as a list; `nil` when `v`
is empty. A sorted map or set answers its entries or members in reverse order. Anything
else (`nil`, a list, a seq, a string, a hash map) signals, like the oracle. As a value a
one-argument function.

```clojure
(println (rseq [1 2 3]))  ; (3 2 1)
(println (rseq []))       ; nil
```
