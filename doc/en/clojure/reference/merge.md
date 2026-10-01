# merge

`(merge m ...)`

Answers one fresh table over every argument's pairs, later maps winning; no argument is
mutated. `(merge)` is `nil`, and a merge of all-`nil` arguments is `nil` too.

```clojure
(println (count (merge {:a 1} {:b 2})))     ; 2
(println (get (merge {:a 1} {:a 2 :b 3}) :a)) ; 2
(println (get (merge nil {:a 1}) :a))       ; 1
(println (merge))                           ; nil
```
