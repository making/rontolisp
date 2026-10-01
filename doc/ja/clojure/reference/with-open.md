# with-open

`(with-open [name init ...] body...)`

各値を本体の周りに束縛し、抜けるとき（どの経路でも）逆順に閉じます。`unwind-protect` 越しです。閉じる処理は `close` メソッド呼び出しのため、ホストオブジェクトのある場面（インタープリターと JVM。wasm は他の interop 同様 `java:` を拒否します）では Java の closeable が使えます。

```clojure
(println (with-open [] :ok)) ; :ok
```
