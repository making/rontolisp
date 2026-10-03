# rseq

`(rseq v)`

`clojure.core/rseq`: the members of the vector `v`, last first, as a list; `nil` when `v`
is empty. Anything but a vector (`nil`, a list, a seq, a string, a map) signals, like the
oracle. As a value a one-argument function.

```clojure
(println (rseq [1 2 3]))  ; (3 2 1)
(println (rseq []))       ; nil
```
