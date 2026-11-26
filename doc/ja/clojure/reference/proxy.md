# proxy

`(proxy [ClassOrInterface...] [args...] methods...)`

ベクタがインターフェースだけならそのすべてを実装する 1 つのホストオブジェクトを、クラスを先頭に含むならそのスーパークラス（と後に続くインターフェース）を継承する 1 つのホストオブジェクトを組み立てます。引数ベクタが選ぶコンストラクタで作られます。

各 `(method [params...] body...)` はメソッド名で選ばれるディスパッチの腕になるので、2 つのインターフェースが宣言する同じ名前は 1 つの本体を実行します。インターフェースだけのメソッドは Java の引数だけを受け取ります -- `this` はありません。proxy が定義していないインターフェースメソッドを呼ぶと `no proxy method: <name>` を送出します。

スーパークラスがある場合、各本体はプロキシオブジェクトを `this` に束縛し、`(proxy-super method args...)` はスーパークラスの実装を呼びます。名前を挙げたメソッドは本体を実行します（`toString`/`equals`/`hashCode` を含みます）。名前を挙げなかったメソッドは、クラスが実装していれば継承し、実装がなければ呼ばれたときにメソッド名とともに拒否されます。ベクタ内の 2 つめ以降のクラス、重複したメソッド、`final` のスーパークラス、ベクタでない引数ベクタは名前を上げて拒否され、フィールドへの書き込み（`set!`）も拒否されます。インタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

Java がメソッドに渡す `byte[]` はそれを格納領域とする[バイト配列](byte-array.md)で、本体がそこへ書き込んだ値はメソッドが戻った時点で Java から読めます。参照を返すメソッドは、値自身のオブジェクトを Java に渡します。バイト配列はその `byte[]`、ベクタは `List`、マップは `Map`、キーワードは Java がキーワードとして返すオブジェクトです。

```clojure
(println (.get (proxy [java.util.function.Supplier] [] (get [] "p")))) ; p
```

```clojure
(def p (proxy [java.util.function.Consumer java.util.function.IntConsumer] []
         (accept [x] (println :got x))))
(.forEach (java.util.List/of "s") p)                   ; :got s
(.forEach (java.util.stream.IntStream/range 3 4) p)    ; :got 3
```

```clojure
(println (.lastModified (proxy [java.io.File] ["recent"] (lastModified [] 42)))) ; 42
(println (.getName (proxy [java.io.File] ["recent"] (lastModified [] 42))))      ; recent
```

```clojure
(def f (proxy [java.io.File] ["x"]
         (toString [] (str "super-was:" (proxy-super toString)))))
(println (.toString f)) ; super-was:x
```

```clojure
(def in (proxy [java.io.InputStream] []
          (read [buf off len] (aset buf off (byte 7)) 1)))
(def b (byte-array 2))
(println (.read in b 0 2) (vec b)) ; 1 [7 0]
```
