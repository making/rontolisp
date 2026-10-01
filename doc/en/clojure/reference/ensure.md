# ensure

`(ensure r)`

Answers the ref itself, requiring a transaction like the oracle (where it also
pins the ref into the transaction's read set -- a no-op here, with nothing to
isolate against).

```clojure
(def r (ref 7))
(println (dosync (ensure r) :ok)) ; :ok
```
