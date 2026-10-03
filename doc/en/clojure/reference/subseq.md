# subseq

`(subseq sc test key)` / `(subseq sc start-test start-key end-test end-key)`

`clojure.core/subseq`: the members (or `[k v]` entries) of the sorted collection `sc`
whose keys pass the tests against the keys, in order, the oracle's way: with a `>` or `>=`
test the walk starts at `key`, with any other it starts at the first member and runs
while the test holds; the five-argument form starts at the first bound and runs while
the second holds. The tests read the comparator's answer (`(test (cmp member key) 0)`).
A strict list, `nil` when nothing passes (where the oracle's walk from the first member
prints `()`). A test passed as a value is recognized by how it answers `(1 0)`, `(0 0)`
and `(-1 0)` (the oracle compares it with the core functions). Anything but a sorted
collection signals. As a value three or five arguments.

```clojure
(println (subseq (sorted-set 1 2 3 4) > 2))        ; (3 4)
(println (subseq (sorted-set 1 2 3 4) >= 2 < 4))   ; (2 3)
(println (subseq (sorted-map :a 1 :b 2 :c 3) > :a)) ; ([:b 2] [:c 3])
```
