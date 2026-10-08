# instance?

`(instance? Class x)`

`x` がそのクラスのインスタンスのとき `true` です（オラクルの `isInstance` と同じ）。クラスや
インタフェースは、オラクルでのクラスがそれ自身であるか、それを実装する種類の値すべてについて
答えます。`java.util.List` はベクタ・リスト・遅延 seq、`java.util.Map` はマップと record、
`clojure.lang.IFn` は関数・キーワード・シンボル・マップ・セット・ベクタ・var、`Comparable` は
文字列・数値・キーワード・ベクタ、`java.io.Writer` は `*out*` について `true` です。`Object` は
`nil` 以外のすべての値です。既知の record/deftype 名はディスパッチタグの検査になります。
プロトコルのインタフェース（`user.P`。名前空間と名前はオラクルと同じく munge され、
`my_app.core.my_p` のようになります）は、本体でそのプロトコルを挙げた record・deftype・`reify`
について、メソッドの有無によらず `true` です。`extend-type` や `extend-protocol` の対象は
インスタンスではありません（`satisfies?` は `true` です）。本体が実装した `clojure.lang` の
インタフェース（[reify](reify.md#host-interfaces) を参照。`Counted`、`IFn` など）は、その値に
ついて `true` で、そのスーパーインタフェース（`Indexed` に対する `Counted`）も同様です。
throwable クラス（`Exception`、`IllegalArgumentException`、`clojure.lang.ExceptionInfo`、
ドット付きや import した名前）は、例外と実行時エラーをそのクラス（`class` が返すクラス）か
そのサブクラスであるかで検査します。[clojure.java.io](clojure-java-io.md) の値はどの
バックエンドでもそのクラスと上位型で検査するので、`(instance? java.io.File (java.io.File. "x"))`
と `(instance? java.io.Closeable (clojure.java.io/input-stream f))` は `true` です。インタプリタと
JVM ではホストのオブジェクトをホストのクラスで検査するので、
`(instance? Number (java.math.BigDecimal. "1"))` は `true` です。ここではどの値もインスタンスに
ならないクラス（`Integer`）は `false`、どのクラスでもない名前は `unknown name` になります。

```clojure
(println (instance? String "a") (instance? String 1)) ; true false
(println (instance? java.util.List [1]) (instance? clojure.lang.IFn :k) (instance? java.util.Map [1])) ; true true false
(println (instance? RuntimeException (IllegalArgumentException. "x")) (instance? RuntimeException (Exception. "x"))) ; true false
(defprotocol P (m [x]))
(defrecord R [] P (m [_] 1))
(defrecord S [])
(extend-type S P (m [_] 2))
(println (instance? user.P (->R)) (instance? user.P (->S)) (satisfies? P (->S))) ; true false true
```
