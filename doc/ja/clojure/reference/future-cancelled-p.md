# future-cancelled?

`(future-cancelled? f)`

`clojure.core/future-cancelled?`: ホストの `java.util.concurrent.Future` の `isCancelled` を返します。これは interop でしか作れない（インタプリタと JVM）値です。[HTTP クライアント](http-client.md)が `:async true` で返すフューチャーは取り消されることがないので `false` です。それ以外の値には `ClassCastException`（`nil` は `NullPointerException`）を投げます。オラクルのキャストと同じです。値としては1引数の関数です。

```clojure
(import '(java.util.concurrent CompletableFuture))
(println (future-cancelled? (CompletableFuture/completedFuture 1)) (future-cancelled? (doto (CompletableFuture.) (.cancel true))))  ; false true
```
