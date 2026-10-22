# sync

`(sync flags body...)`

Runs the body as one transaction like `dosync` -- dropping
the flags unevaluated, like the oracle's `flags-ignored-for-now` -- and answers its
last value. A nested `sync` joins the running transaction, and a conflicting commit
retries the body like `dosync` (see [Deviations](../deviations.md)).

```clojure
(def r (ref 0))
(println (sync nil (alter r inc) (alter r inc))) ; 2
```
