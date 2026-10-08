# future?

`(future? x)`

`clojure.core/future?`: ホストの `java.util.concurrent.Future`（interop でしか作れない。インタプリタと JVM）と、[HTTP クライアント](http-client.md)が `:async true` で返すフューチャー（すべてのバックエンド）なら `true` を返します。`future` は拒否されるため、ほかの値は `false` です。引数は評価されます。値としては1引数の関数です。

```clojure
(import '(java.util.concurrent CompletableFuture))
(println (future? (CompletableFuture/completedFuture 1)) (future? 1))  ; true false
```
