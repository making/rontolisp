# rand-int

`(rand-int bound)`

The truncation of one scaled draw from the program-owned generator: an int in
`[0,bound)` for a positive bound; `0` answers `0` and negative bounds negative,
like the oracle's int-of-rand (no domain check). As a value a one-argument
lambda.

```clojure
(println (rand-int 1)) ; 0
(println (contains? #{0 1 2} (rand-int 3))) ; true
```
