# assoc

`(assoc m k v ...)`

Answers a fresh map over `m`'s pairs plus the given ones, later pairs winning; `m` is
never mutated. `assoc` onto `nil` builds from empty. Odd pair counts are refused. Keys
compare by `=`: a key `=` to one already there replaces its value and keeps that key.

Onto a record the entries join the entry table and the type survives.

As a value a map plus a rest list of pairs; an odd rest count signals at run time.

Transients (`assoc!`) are refused by name.

```clojure
(def mm-base {:a 1})
(println (get (assoc mm-base :b 2) :b)) ; 2
(println (get mm-base :b))              ; nil
(println (get (assoc nil :a 1) :a))     ; 1
(println (get ((fn [f] (f {:a 1} :b 2)) assoc) :b)) ; 2
(println (assoc {[1 2] :a} '(1 2) :b)) ; {[1 2] :b}
```
