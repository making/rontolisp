# map-entry?

`(map-entry? x)`

`clojure.core/map-entry?`: `true` for a map entry. An entry is a plain two-member vector
here, so every two-member vector answers `true`, where the oracle answers `false` for a
`[k v]` it built itself (an entry from `first`, `seq` or `find` answers `true` in both).
Every other value is `false`. As a value a one-argument function.

```clojure
(println (map-entry? (first {:a 1})))  ; true
(println (map-entry? {:a 1}))          ; false
(println (map-entry? [1 2 3]))         ; false
```
