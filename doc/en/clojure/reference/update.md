# update

`(update m k f args...)`

Answers a fresh map with `k` rewritten to `(apply f (get m k) args...)`. A
missing key applies `f` to `nil` (which signals for arithmetic, like the
oracle). As a value a map, a key, a function and any extra arguments.

```clojure
(println (update {:a 1} :a inc)) ; {:a 2}
(println (update {:a 1} :a + 10 20)) ; {:a 31}
```
