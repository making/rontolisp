# future-done?

`(future-done? f)`

`clojure.core/future-done?`: the host `java.util.concurrent.Future`'s `isDone`, which only interop builds (interpreter and JVM), and for the future the [HTTP client](http-client.md) answers under `:async true` (every backend), whether its response has arrived. Any other value signals `ClassCastException` (`nil`: `NullPointerException`), as the oracle's cast does. As a value a one-argument function.

```clojure
(import '(java.util.concurrent CompletableFuture))
(println (future-done? (CompletableFuture/completedFuture 1)) (future-done? (CompletableFuture.)))  ; true false
```
