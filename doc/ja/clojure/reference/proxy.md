# proxy

`(proxy [Interface] decls... methods...)`

1 つのインターフェースを実装するホストオブジェクトを組み立てます。各 `(method [params...] body...)` がディスパッチの腕になり、メソッドは Java の引数だけを受け取ります -- `this` はありません。コンストラクタ引数なし、スーパークラスなし、インターフェース 1 つ、単一アリティのメソッドで、より広い形は名前を上げて拒否され、フィールドへの書き込み（`set!`）も拒否されます。インタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

```clojure
(println (.toString (proxy [java.lang.Object] [] (toString [] "p")))) ; p
```
