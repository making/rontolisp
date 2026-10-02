# proxy

`(proxy [Interface...] decls... methods...)`

ベクタ内のすべてのインターフェースを実装する 1 つのホストオブジェクトを組み立てます。各 `(method [params...] body...)` はメソッド名で選ばれるディスパッチの腕になるので、2 つのインターフェースが宣言する同じ名前は 1 つの本体を実行します。メソッドは Java の引数だけを受け取ります -- `this` はありません。proxy が定義していないインターフェースメソッドを呼ぶと `no proxy method: <name>` を送出します。スーパークラス（ベクタ内のクラス）、コンストラクタ引数、`toString`/`equals`/`hashCode`（オブジェクトは `Object` のものを保ちます）、複数アリティのメソッドは名前を上げて拒否され、フィールドへの書き込み（`set!`）も拒否されます。インタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

```clojure
(println (.get (proxy [java.util.function.Supplier] [] (get [] "p")))) ; p
```

```clojure
(def p (proxy [java.util.function.Consumer java.util.function.IntConsumer] []
         (accept [x] (println :got x))))
(.forEach (java.util.List/of "s") p)                   ; :got s
(.forEach (java.util.stream.IntStream/range 3 4) p)    ; :got 3
```
