# io!

`(io! body...)`

Runs the body and answers its last value -- unless a transaction is running, when it
signals the oracle's `IllegalStateException` (`I/O in transaction`), like `await`
inside one.

```clojure
(println (io! (+ 1 2))) ; 3
(println (try (dosync (io! 1)) (catch IllegalStateException e (ex-message e)))) ; I/O in transaction
```
