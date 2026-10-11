# ensure

`(ensure r)`

Answers the ref's value, requiring a transaction like the oracle (where it also
protects the ref from other transactions' writes -- here no other transaction runs
meanwhile).

```clojure
(def r (ref 7))
(println (dosync (ensure r))) ; 7
```
