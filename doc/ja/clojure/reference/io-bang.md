# io!

`(io! body...)`

本体を実行し、最後の値を返します。ただしトランザクションの実行中は oracle と同じ `IllegalStateException`（`I/O in transaction`）を上げます（トランザクションの中の `await` と同じです）。

```clojure
(println (io! (+ 1 2))) ; 3
(println (try (dosync (io! 1)) (catch IllegalStateException e (ex-message e)))) ; I/O in transaction
```
