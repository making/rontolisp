# vreset!

`(vreset! volatile v)`

Stores `v`, answering it -- `reset!` for a volatile.

```clojure
(def v (volatile! 1))
(println (vreset! v 2)) ; 2
(println @v) ; 2
```
