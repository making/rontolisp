# all-ns

`(all-ns)`

The namespaces the program created before the call -- its own, the ones it required and the
ones the oracle loads first -- as namespace objects, minus removed ones, like the oracle's.
Order is unspecified; only membership pins. As a value no arguments.

```clojure
(println (contains? (set (map str (all-ns))) "clojure.core")) ; true
```
