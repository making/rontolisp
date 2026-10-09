# with-open

`(with-open [name init ...] body...)`

各値を本体の周りに束縛し、抜けるとき（どの経路でも）逆順に閉じます。`unwind-protect` 越しです。ストリーム値（`clojure.java.io/reader` など）は `close` で直接閉じます。それ以外は `close` メソッド呼び出しで閉じます。[clojure.java.io](clojure-java-io.md) のバイトストリームはどのバックエンドでも、Java の closeable はホストオブジェクトのある場面（インタープリターと JVM。wasm は他の interop 同様 `java:` を拒否します）で使えます。

```clojure
(println (with-open [] :ok)) ; :ok
```
