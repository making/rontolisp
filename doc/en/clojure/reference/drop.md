# drop

`(drop n coll)`

Answers the seq view of `coll` without its first `n` elements, computed by `nthcdr` over
the view. Strict; a drop longer than the collection answers the empty seq.

Deviation: an over-long drop answers `nil`, where the oracle prints `()`.

```clojure
(println (drop 2 [1 2 3 4]))  ; (3 4)
(println (drop 8 (range 10))) ; (8 9)
(println (drop 10 [1 2]))     ; nil
```
