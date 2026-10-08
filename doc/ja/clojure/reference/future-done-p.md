# future-done?

`(future-done? f)`

`clojure.core/future-done?`: ホストの `java.util.concurrent.Future`（interop でしか作れない。インタプリタと JVM）には `isDone` を、[HTTP クライアント](http-client.md)が `:async true` で返すフューチャー（すべてのバックエンド）にはレスポンスが届いたかを返します。それ以外の値には `ClassCastException`（`nil` は `NullPointerException`）を投げます。オラクルのキャストと同じです。値としては1引数の関数です。

```clojure
(import '(java.util.concurrent CompletableFuture))
(println (future-done? (CompletableFuture/completedFuture 1)) (future-done? (CompletableFuture.)))  ; true false
```
