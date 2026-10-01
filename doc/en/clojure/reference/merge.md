# merge

`(merge m ...)`

Answers one fresh table over every argument's pairs, later maps winning; no argument is
mutated. `(merge)` is `nil`, and a merge of all-`nil` arguments is `nil` too. The result keeps a record's type only when the first non-`nil` argument is one, like the oracle.

As a value a rest lambda over the maps.

```clojure
(println (count (merge {:a 1} {:b 2})))     ; 2
(println (get (merge {:a 1} {:a 2 :b 3}) :a)) ; 2
(println (get (merge nil {:a 1}) :a))       ; 1
(println (merge))                           ; nil
(println (count ((fn [f] (f {:a 1} {:b 2})) merge))) ; 2
```
