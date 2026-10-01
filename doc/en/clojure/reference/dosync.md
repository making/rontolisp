# dosync

`(dosync body...)`

Runs the body with a transaction open and answers its last value. There is a
single thread, so a transaction is a dynamic extent, not an isolation: nothing
retries and nothing conflicts. `alter`/`commute`/`ref-set`/`ensure` outside one
signal (`No transaction running`).

```clojure
(def r (ref 0))
(println (dosync (alter r inc) (alter r inc))) ; 2
(println @r) ; 2
```
