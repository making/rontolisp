# filterv

`(filterv pred coll)`

The strict vector arm of `filter` under Clojure truthiness (a false object drops
like `nil`); collections work as predicates through the dispatcher. As a value a
two-argument lambda.

```clojure
(println (filterv odd? [1 2 3 4])) ; [1 3]
(println (filterv #{:h} [:h :t])) ; [:h]
```
