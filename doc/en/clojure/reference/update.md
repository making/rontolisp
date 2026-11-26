# update

`(update m k f args...)`

Answers a fresh map with `k` rewritten to `(apply f (get m k) args...)`. A
missing key applies `f` to `nil` (which signals for arithmetic, like the
oracle). A vector is updated by index like [assoc](assoc.md), the index equal to the
count appending. As a value a map, a key, a function and any extra arguments.

```clojure
(println (update {:a 1} :a inc)) ; {:a 2}
(println (update {:a 1} :a + 10 20)) ; {:a 31}
(println (update [1 2 3] 0 inc)) ; [2 2 3]
```
