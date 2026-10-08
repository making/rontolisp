# future-cancel

`(future-cancel f)`

`clojure.core/future-cancel`: ホストの `java.util.concurrent.Future` の `cancel(true)` を呼び、取り消された状態になったかを返します（すでに完了していれば `false`）。これは interop でしか作れない（インタプリタと JVM）値です。[HTTP クライアント](http-client.md)が `:async true` で返すフューチャーは取り消せないので、どのバックエンドでも `false` を返します。それ以外の値には `ClassCastException`（`nil` は `NullPointerException`）を投げます。オラクルのキャストと同じです。値としては1引数の関数です。

```clojure
(import '(java.util.concurrent CompletableFuture))
(println (future-cancel (CompletableFuture.)) (future-cancel (CompletableFuture/completedFuture 1)))  ; true false
```
