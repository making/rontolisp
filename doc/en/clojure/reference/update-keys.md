# update-keys

`(update-keys m f)`

Answers a fresh map of `m`'s entries under `(f key)`. A record or a Java `Map` answers a plain map,
`nil` the empty map, and a vector keys its members by index, like the oracle. Two
keys that `f` maps together keep one entry (which one follows the walk order). As a
value a two-argument function.

```clojure
(prn (update-keys {:a 1} name)) ; {"a" 1}
```
