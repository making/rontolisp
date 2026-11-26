# vreset!

`(vreset! volatile v)`

`v` を格納し、それを返します -- volatile 向けの `reset!` です。

```clojure
(def v (volatile! 1))
(println (vreset! v 2)) ; 2
(println @v) ; 2
```
