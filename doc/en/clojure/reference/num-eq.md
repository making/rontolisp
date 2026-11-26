# ==

`(== x...)`

Numeric equality over one or more numbers: `1` and `1.0` are equal, as are `0.0` and `-0.0`; a non-number signals. `=` keeps the categories apart.

```clojure
(println (== 1 1.0)) ; true
(println (= 1 1.0)) ; false
(println (== 0.0 -0.0)) ; true
```
