# future-cancel

`(future-cancel f)`

`clojure.core/future-cancel`: the host `java.util.concurrent.Future`'s `cancel(true)`, answering whether it is now cancelled (`false` for one already done), which only interop builds (interpreter and JVM). The future the [HTTP client](http-client.md) answers under `:async true` cannot be cancelled: `false` on every backend. Any other value signals `ClassCastException` (`nil`: `NullPointerException`), as the oracle's cast does. As a value a one-argument function.

```clojure
(import '(java.util.concurrent CompletableFuture))
(println (future-cancel (CompletableFuture.)) (future-cancel (CompletableFuture/completedFuture 1)))  ; true false
```
