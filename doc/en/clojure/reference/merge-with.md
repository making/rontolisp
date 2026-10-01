# merge-with

`(merge-with f maps...)`

Answers one fresh map over every map's pairs, conflicts resolved through `f`
over the old and new values. `nil` maps contribute nothing; `(merge-with f)`
is `nil`. As a value the function, then any number of maps.

```clojure
(println (merge-with + {:a 1} {:a 2})) ; {:a 3}
```
