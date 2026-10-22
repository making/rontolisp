# future

`(future & body)`

`clojure.core/future`: runs `body` on its own thread on the interpreter and the JVM, conveying the creation-time bindings (like `bound-fn*`), and answers a future: `deref` (and `@`) blocks for its value, and a failure surfaces as `ExecutionException` over the body's condition. `future?` is `true` of it, `future-done?` tells whether the body settled, `realized?` the same, and `future-cancel` interrupts it, answering whether that won. On the WASM backends, single-threaded by construction, the call refuses instead. A macro, like the oracle's.

```clojure
(println @(future (do 1 2))) ; 2
```
