# future

`(future & body)`

`clojure.core/future`: スレッドが存在する場所（インタプリタと JVM）では `body` を独自のスレッドで走らせ、生成時の束縛を引き継ぎ（`bound-fn*` と同じ）、future を答えます。`deref`（と `@`）はその値をブロックして待ち、失敗は本体の条件を原因とする `ExecutionException` として現れます。`future?` はそれに `true` を答え、`future-done?` は本体が settled したかを、`realized?` も同じことを答え、`future-cancel` はそれに割り込み、勝ったかを答えます。シングルスレッドの WASM バックエンドでは呼び出しが代わりに拒否されます。オラクルと同様のマクロです。

```clojure
(println @(future (do 1 2))) ; 2
```
