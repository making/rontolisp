# class

`(class x)`

値のクラスを、オラクルが印字・比較するとおりに返します。`java.lang.String`・
`java.lang.Long`・`java.lang.Double`・`java.lang.Character`・`java.lang.Boolean`・
`clojure.lang.Keyword`・`clojure.lang.Symbol`・`clojure.lang.PersistentVector`・
`clojure.lang.PersistentArrayMap`（9エントリ以上は `clojure.lang.PersistentHashMap`）・
`clojure.lang.PersistentHashSet`・`clojure.lang.PersistentTreeMap`・`clojure.lang.PersistentList`・
`clojure.lang.LazySeq`・`clojure.lang.Atom` などです。nil には nil を返します。関数はオラクルが
それをコンパイルするクラスを返し、`.getClass` も同じものを返します。`defn` は `user$f`、core 関数は
`clojure.core$name`、無名 fn は `clojure.lang.AFunction$fn` で、どれも `clojure.lang.AFunction` を
継承し、クラスが `java.lang.Class` であるところではそのように定義したものです。
`(= (class "a") String)` と `(class? (class 1))` は `true` です。

インタプリタと、Java 相互運用を使う JVM プログラムでは、クラスは `java.lang.Class` です。
JDK のクラスはホスト自身のもの、`clojure.lang` のクラスはオラクルと同じ名前・修飾子・スーパークラス・
インタフェースで定義したもので、`.getName`・`.isInterface`・`.isAssignableFrom`・`Class/forName`
やクラスを受け取る Java メソッドはオラクルと同じ答えを返します
（`(.isAssignableFrom java.util.List (class []))` は `true`）。宣言されたメンバは、このフロントエンドが
答える静的メンバ（`clojure.lang.PersistentArrayMap/createAsIfByAssoc` など）です。
それ以外（wasm バックエンド、相互運用を使わない JVM プログラム、ネイティブバイナリ）では、
印字・比較・ハッシュ・`class?` が同じクラス値です。

record/deftype はそのクラスを返します。これは名前を値として評価したものと同じです（`user.R`、
`str` は `"class user.R"`）。ホストがあるところではそのクラスはそのために定義されたもので、
オラクルのスーパークラスとインタフェース（本体のもの、`IRecord` または `IType` のもの、
`clojure.lang` のものも定義されます）を持つので、それに対する
`.isAssignableFrom clojure.lang.IRecord`、その名前の `Class/forName`、`Class` を取る Java の
メソッドはオラクルと同じに答えます。`(bases (class r))` もオラクルと同じです。`reify` はその
フォームのクラスを、`definterface` の名前はそのインタフェースを返します（`class?`・
`.isInterface`・`(bases I)` は nil で、オラクルと同じに答えます）。例外と実行時エラーはそのクラス（`clojure.lang.ExceptionInfo`・
`java.lang.IllegalArgumentException`。実行時エラーと拒否はオラクルがその箇所で投げるクラス、
クラスを示さないエラーは `java.lang.RuntimeException`）、ストリームはプリンタが示すクラス
（`java.io.OutputStreamWriter`・`java.io.StringWriter` など）、ホストオブジェクトはホストクラス
（インタプリタと JVM）を返します。値としては1引数ラムダです。

逸脱: ここでは `1N` は `Long`、小数は `Ratio` です。縮小キャストは `Long` を返します。
無名 fn はすべて1つのクラス（オラクルは `fn` フォームごとに作ります）で、可変長引数の fn も
`AFunction` を継承します（オラクルは `RestFn`）。遅延シーケンスでないシーケンスはすべて `clojure.lang.PersistentList`、2要素のベクタは
`PersistentVector`（オラクルのマップエントリは `MapEntry`）、型付き配列は `PersistentVector` です。
バイト配列のクラスはバイナリ名 `[B` で印字されます（オラクルは `byte/1`）。

```clojure
(println (class "a") (class 1) (class [1]) (class nil)) ; java.lang.String java.lang.Long clojure.lang.PersistentVector nil
(println (= (class "a") String) (class? (class 1))) ; true true
(println (class (ex-info "m" {})) (try (+ 1 "a") (catch Exception e (class e)))) ; clojure.lang.ExceptionInfo java.lang.ClassCastException
```
