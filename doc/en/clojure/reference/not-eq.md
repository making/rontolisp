# not=

`(not= x...)`

The negation of `=` over the same neighbour chain: `true` when some adjacent pair differs.

```clojure
(println (not= 1 2)) ; true
(println (not= 1 2 1)) ; true
(println (not= 1 1)) ; false
```
