# future-cancel

`(future-cancel f)`

`clojure.core/future-cancel`: ホストの `java.util.concurrent.Future` の `cancel(true)` を呼び、取り消された状態になったかを返します（すでに完了していれば `false`）。これは interop でしか作れない（インタプリタと JVM）値です。それ以外の値には `ClassCastException`（`nil` は `NullPointerException`）を投げます。オラクルのキャストと同じで、wasm ではどの呼び出しもこうなります。値としては1引数の関数です。

```clojure
(import '(java.util.concurrent CompletableFuture))
(println (future-cancel (CompletableFuture.)) (future-cancel (CompletableFuture/completedFuture 1)))  ; true false
```
