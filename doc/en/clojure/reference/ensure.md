# ensure

`(ensure r)`

Answers the ref's value, requiring a transaction like the oracle -- and pinning
the read until commit, so a writer of the ref waits for the transaction like the
oracle's.

```clojure
(def r (ref 7))
(println (dosync (ensure r))) ; 7
```
