# coll?

`(coll? x)`

`true` for a list, lazy seq, vector, map, set or record. `nil`, the booleans, strings (which
the runtime stores as vectors), characters, numbers, keywords, atoms, vars and regex patterns
are no collections, like the oracle. `nil` is the empty list here, so `(coll? ())` is `false`
where the oracle answers `true`. As a value a one-argument lambda answering `T`-or-`false`.

```clojure
(println (coll? [1]) (coll? nil)) ; true false
```
