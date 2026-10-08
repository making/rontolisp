# future?

`(future? x)`

`clojure.core/future?`: `true` for a host `java.util.concurrent.Future`, which only interop builds (interpreter and JVM), and for the future the [HTTP client](http-client.md) answers under `:async true` (every backend); any other value is `false`, since `future` is refused. The argument is still evaluated. As a value a one-argument function.

```clojure
(import '(java.util.concurrent CompletableFuture))
(println (future? (CompletableFuture/completedFuture 1)) (future? 1))  ; true false
```
