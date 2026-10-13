# .name / .-name

`(.method receiver args...)` `(.-field receiver)`

`.` の糖衣です。ドット付きの名前は receiver へのメソッド呼び出し、`.-` ドット付きの名前はインスタンスフィールドの読み取りです。interop のどこでもそうであるように receiver が経路を決めるため、文字列の receiver は対応する core 操作を取り（int の `.indexOf` はその文字を探す `indexOf(int)` のオーバーロードです）、それ以外の `String` メソッドは文字列を `String` として呼びます。ホストオブジェクトでない値の `.toString` は、全バックエンドでその `str` の綴りを返します。コレクション・キーワード・シンボル・比・atom もホストオブジェクトを持たないため、`clojure.lang`/`java.util` のよく使うメソッド（`.count`・`.size`・`.isEmpty`・`.get`・`.nth`・`.valAt`・`.contains`・`.containsKey`・`.indexOf`・`.getName`・`.getNamespace`・`.numerator`・`.deref` など）は全バックエンドで対応する core 関数を通して答えます。`.equiv` は `=` で、`.equals` はオラクルの Java の `equals` です。record はそのエントリを持つマップ・ソート済みマップ・別の record と等しく、コレクション内の double は `Double.equals` で比べます（`(.equals [0.0] [-0.0])` は false、`(.equals [##NaN] [##NaN])` は true）。オラクルのクラスが実装する JDK インターフェースのそれ以外のメソッド（`.toArray`・`.containsAll`・`.entrySet`・`.stream`・`.sort`）は、インタープリターと JVM では値が Java へ渡るときの読み取り専用の Java オブジェクトに対して呼び、wasm バックエンドでは名前を挙げて拒否します。fn は `.invoke`・`.applyTo`・`.call`・`.run`・`.compare`（真偽値の戻り値は Clojure の `AFunction.compare` として読みます）に全バックエンドで答え、`Comparator` のそれ以外のメソッド（`.reversed`・`.thenComparing`）は同じ方法で fn そのものであるオブジェクトに対して呼びます。そのクラスにないメソッドはオラクルと同じ文言で拒否し（`No matching field found: toArray for class clojure.lang.Keyword`）、オラクルのクラスが持ちうるがここでは答えないメソッド（`.reduce`・`.meta`）は名前を挙げて拒否します。record・deftype・reify は全バックエンドでそのクラスが持つものに答えます。本体が実装するプロトコルメソッドはそれを呼び（`(.m r)`）、宣言フィールドを名指す引数なしの名前はそのフィールドを読みます（`(.a r)`）。`extend-type` のメソッド、宣言されていない名前、可変フィールドはオラクルと同じ文言で拒否します（`No matching field found: q for class user.R`）。プログラムが定義したどの record・deftype のプロトコルメソッドでもフィールドでもない名前は、コレクションと同じように扱います（record は `java.util.Map` です）。それ以外はインタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

引数なしの `(StringBuilder.)`（`(new StringBuilder)`・`(StringBuffer.)` も）は、`(new java.io.StringWriter)` と同じく全バックエンドで文字列出力ストリームです。1引数の `.append` はその値の `str` を書き込み（nil は `"null"` を書き込みます）、ビルダー自身を返します。`.toString` と `str` はここまでのテキストを返し、`.length`・`.charAt`・`.setLength` はその読み取りと伸縮です（`.setLength` は nil を返します）。1引数の構築はそのテキストでストリームを満たして開始します（`(StringBuilder. "ab")`・`(new StringBuilder "ab")`・`(StringBuffer. "ab")`）。整数の引数は容量です（無視し、ストリームは空で開始します）。それ以外の引数は `No matching ctor found for class ...` として拒否します。1引数の `java.io.StringWriter`（`(java.io.StringWriter. 16)`・`(new java.io.StringWriter 16)`）は代わりに空の文字列出力ストリームです。数値は容量であり（無視し、ストリームは空で開始します）、それ以外はオラクルの `Number` への `ClassCastException` になります（`StringWriter` に `String` のコンストラクタはないため）。nil はオラクルの `NullPointerException` です。`^[...]` タグ付きの構築はホストのオーバーロードを指定したことになり、ホストの構築のまま残るためインタープリターと JVM でのみ動作します。`java.util.Locale/US`（`ROOT`・`ENGLISH` も）はそのロケールの `toString` のテキスト（`"en_US"`・`""`・`"en"`）で、1引数の `.toUpperCase`・`.toLowerCase` が全バックエンドで受け付け、引数なしと同じ写像になります（写像はロケールを無視します）。文字列の `.intern` はその文字列自身で、全バックエンドで動作します。

引数なしの `(java.util.HashMap.)`（`(new java.util.HashMap)` も）は空のマップであり、マップからの1引数の構築はそのマップ自身、2つの数値からの2引数の構築（`(java.util.HashMap. 1024 0.25)`）は空のマップです（ホストがない場合、すなわち wasm バックエンド）。`.get`・`.size`・`.containsKey` などの読み取りは通常のマップと同じようにそれを読みますが、`.put` はオラクルのメッセージなしの `UnsupportedOperationException` を投げます（インタープリターと JVM と同じです。そこでは値が Java へ渡るときに読み取り専用マップになるため）。マップからの `.putAll` はそのエントリーをマップに格納して nil を返します。レコードやソート済みマップへの `.putAll` は同じ `UnsupportedOperationException` を投げます。nil からの構築はオラクルの `NullPointerException` です。ホストがある場合（インタープリターと JVM）は可変の `java.util.HashMap` のまま残り、それ以外の引数の形や `^[...]` タグ付きの構築は全域でホストの構築のまま残ります。

```clojure
(println (.toUpperCase "hi")) ; HI
(println (.length "hi")) ; 2
(println (.compareTo "a" "b")) ; -1
(println (.toString [1 "a"])) ; [1 "a"]
(println (.count [1 2 3]) (.get {:a 1} :a) (.getName :k)) ; 3 1 k
(defprotocol P (m [this]))
(defrecord R [a] P (m [this] (str "m" a)))
(println (.m (->R 1)) (.a (->R 1))) ; m1 1
(let [sb (StringBuilder.)] (.append sb "a") (.append sb 1) (println (str sb) (.length sb))) ; a1 2
(println (str (StringBuilder. "ab") (StringBuffer. 16))) ; ab
(println (let [w (java.io.StringWriter. 16)] (.append w "ab") (str w))) ; ab
(println (.toUpperCase "straße" java.util.Locale/US) (.intern "hi")) ; STRASSE hi
(let [h (doto (java.util.HashMap. 8 0.5) (.putAll {:a 1}))] (println (.get h :a) (.size h))) ; 1 1
```
