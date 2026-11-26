# rsubseq

`(rsubseq sc test key)` / `(rsubseq sc start-test start-key end-test end-key)`

`clojure.core/rsubseq`: `subseq` walking backwards: with a `<` or `<=` test the walk
starts at `key`, with any other at the last member; the five-argument form starts at the
end bound and runs while the start bound holds. A strict list, `nil` when nothing
passes. As a value three or five arguments.

```clojure
(println (rsubseq (sorted-set 1 2 3 4) < 3))        ; (2 1)
(println (rsubseq (sorted-set 1 2 3 4) >= 2 <= 3))  ; (3 2)
```
