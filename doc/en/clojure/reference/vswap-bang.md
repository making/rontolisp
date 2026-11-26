# vswap!

`(vswap! volatile f x...)`

Applies `f` to the volatile's value and the extra arguments and stores the answer, answering
the new value -- `swap!` spelled for a volatile.

```clojure
(def v (volatile! 1))
(println (vswap! v + 2)) ; 3
(println @v) ; 3
```
