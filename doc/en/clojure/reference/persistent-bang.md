# persistent!

`(persistent! tr)`

The collection the transient `tr` edited, handed over without a copy; `tr` then refuses
every verb as the oracle's `IllegalAccessError` (`Transient used after persistent!
call`). A type implementing `ITransientCollection` answers its `persistent`. As a value a
one-argument function.

```clojure
(println (persistent! (assoc! (transient {}) :a 1))) ; {:a 1}
(let [t (transient [])] (persistent! t) (println (try (count t) (catch Throwable e (ex-message e))))) ; Transient used after persistent! call
```
