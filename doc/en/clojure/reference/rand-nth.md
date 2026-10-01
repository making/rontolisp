# rand-nth

`(rand-nth coll)`

One member by one scaled draw from the program-owned generator. `nil` answers
`nil`; an empty vector, string or seq signals, like the oracle; maps and sets
signal too (none are indexed there). Only membership pins -- never the value.

```clojure
(println (rand-nth [:only])) ; :only
(println (contains? #{:a :b} (rand-nth [:a :b]))) ; true
```
