# deref

`(deref ref)`

アトムか volatile、[`reduced`](reduced.md) の中身、または [var](var.md) のルートを読みます。リーダー形式 `@x` は同じ操作です。関数値として動くため、`map`/`reduce` に裸のまま渡せます（その場合の引数は 1 つです）。

インタプリタと JVM では、interop で得た Java の `java.util.concurrent.Future` を `get` で読みます。失敗した Future は `ExecutionException`、取り消された Future は `CancellationException` を投げます。`(deref f ms timeout-val)` は `ms` ミリ秒以内の `get` で、`TimeoutException` のとき `timeout-val` を返します。`Future` でない値に対しては `ClassCastException`（`nil` は `NullPointerException`）を投げます。オラクルの `IBlockingDeref` へのキャストと同じです。[HTTP クライアント](http-client.md)が `:async true` で返すフューチャーも、すべてのバックエンドで同じように読みます。レスポンスを返し、失敗は `ExecutionException` として投げ、引数が 3 つなら `ms` ミリ秒まで待ちます。

```clojure
(def a (atom 1))
(println (deref a)) ; 1
(println @a) ; 1
(println (map deref [(atom 1) (atom 2)])) ; (1 2)
(println @(reduced 3)) ; 3
```
