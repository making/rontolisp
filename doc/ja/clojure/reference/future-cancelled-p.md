# future-cancelled?

`(future-cancelled? f)`

`clojure.core/future-cancelled?`: ホストの `java.util.concurrent.Future` の `isCancelled` を返します。これは interop でしか作れない（インタプリタと JVM）値です。それ以外の値には `ClassCastException`（`nil` は `NullPointerException`）を投げます。オラクルのキャストと同じで、wasm ではどの呼び出しもこうなります。値としては1引数の関数です。

```clojure
(import '(java.util.concurrent CompletableFuture))
(println (future-cancelled? (CompletableFuture/completedFuture 1)) (future-cancelled? (doto (CompletableFuture.) (.cancel true))))  ; false true
```
