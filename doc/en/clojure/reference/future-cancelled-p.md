# future-cancelled?

`(future-cancelled? f)`

`clojure.core/future-cancelled?`: the host `java.util.concurrent.Future`'s `isCancelled`, which only interop builds (interpreter and JVM). Any other value signals `ClassCastException` (`nil`: `NullPointerException`), as the oracle's cast does, so on wasm every call does. As a value a one-argument function.

```clojure
(import '(java.util.concurrent CompletableFuture))
(println (future-cancelled? (CompletableFuture/completedFuture 1)) (future-cancelled? (doto (CompletableFuture.) (.cancel true))))  ; false true
```
