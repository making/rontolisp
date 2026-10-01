# assoc

`(assoc m k v ...)`

Answers a fresh map over `m`'s pairs plus the given ones, later pairs winning; `m` is
never mutated. `assoc` onto `nil` builds from empty. Odd pair counts are refused. Vector
keys compare by identity.

Onto a record the entries join the entry table and the type survives.

As a value a map plus a rest list of pairs; an odd rest count signals at run time.

Deviation: a vector key misses a lookup the oracle answers -- table keys compare by
identity, so `(get (assoc {} [:a] 1) [:a])` is `nil` here. Transients (`assoc!`) are
refused by name.

```clojure
(def mm-base {:a 1})
(println (get (assoc mm-base :b 2) :b)) ; 2
(println (get mm-base :b))              ; nil
(println (get (assoc nil :a 1) :a))     ; 1
(println (get ((fn [f] (f {:a 1} :b 2)) assoc) :b)) ; 2
```
