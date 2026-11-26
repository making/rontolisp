# update-vals

`(update-vals m f)`

Answers `m` with `(f value)` for every value: a map, record or Java `Map` answers a fresh
map, `nil` the empty map, and a vector stays a vector, like the oracle. As a value a
two-argument function.

```clojure
(prn (update-vals {:a 1} inc)) ; {:a 2}
(prn (update-vals [1 2] inc)) ; [2 3]
```
