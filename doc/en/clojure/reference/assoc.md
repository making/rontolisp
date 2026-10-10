# assoc

`(assoc m k v ...)`

Answers a fresh map over `m`'s pairs plus the given ones, later pairs winning; `m` is
never mutated. `assoc` onto `nil` builds from empty. Odd pair counts are refused. Keys
compare by `=`: a key `=` to one already there replaces its value and keeps that key.

Onto a record the entries join the entry table and the type survives.

Onto a vector each key is an index: a fresh vector with that member replaced, an index
equal to the count appending, like the oracle. A non-integer key signals `Key must be
integer`, an index out of range signals. The copy is the whole vector (the oracle's
persistent vector copies one path).

As a value a map plus a rest list of pairs; an odd rest count signals at run time.

[`assoc!`](assoc-bang.md) sets keys of a transient map or vector in place.

```clojure
(def mm-base {:a 1})
(println (get (assoc mm-base :b 2) :b)) ; 2
(println (get mm-base :b))              ; nil
(println (get (assoc nil :a 1) :a))     ; 1
(println (get ((fn [f] (f {:a 1} :b 2)) assoc) :b)) ; 2
(println (assoc {[1 2] :a} '(1 2) :b)) ; {[1 2] :b}
(println (assoc [0 1 2] 0 :y))          ; [:y 1 2]
(println (assoc [0 1] 2 :x))            ; [0 1 :x]
```
