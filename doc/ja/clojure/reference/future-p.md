# future?

`(future? x)`

`clojure.core/future?`: ホストの `java.util.concurrent.Future` なら `true` を返します。これは interop でしか作れない（インタプリタと JVM）ため、wasm ではどの値も `false` です（`future` は拒否され、ほかにそれに当たる値がありません）。引数は評価されます。値としては1引数の関数です。

```clojure
(import '(java.util.concurrent CompletableFuture))
(println (future? (CompletableFuture/completedFuture 1)) (future? 1))  ; true false
```
