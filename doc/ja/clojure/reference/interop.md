# Java interop

interop は java: サーフェスへ低下し、インタプリタと JVM でのみ動きます -- wasm バックエッドは java: を拒否します。クラス名は記されたドット区切り、:import 経由、java.lang 経由（オラクルの既定インポートと同じ一覧で、`IllegalStateException` などの代表的な例外クラスは含まれ、`AutoCloseable` は含まれません）のいずれかで解決されます。文字列レシーバは対応する核操作を返し（Lisp の文字列はホストオブジェクトではない）、ホストオブジェクトでない値の `.toString` は全バックエンドでその `str` の綴りを返します。コレクション・キーワード・シンボル・比・atom は、よく使うメソッド（`.count`・`.get`・`.contains`・`.getName`・`.numerator`・`.deref` など）に全バックエンドで core 関数を通して答え、それ以外のメソッドは名前を挙げて拒否します（[`.name`](dot-name.md)）。[clojure.java.io](clojure-java-io.md) の File・URL・URI・ストリームは全バックエンドでそのクラスのメソッドに答え、Java のメンバーへは表しているホストオブジェクトとして渡ります（インタプリタと JVM）。メンバーが返したホストの `java.io.File`・URL・URI は、`slurp`、`spit`、名前空間の関数、そのクラスに拡張したプロトコルにとって同じ値です。`java.util.Date`・`java.sql.Timestamp`・`java.util.UUID` の構築、`UUID/randomUUID`、`UUID/fromString`、`System/currentTimeMillis` は、全バックエンドで [`#inst` と `#uuid`](instants.md) が読む値を作ります。こうした値はメンバーへホストオブジェクトとして渡り、メンバーが返したホストの Date や UUID はこの値と `=` になります。ホストオブジェクトの `str` はその `toString` を返します。可読な印字（`prn`、`pr-str`、コレクションの中を綴る `str`）では、本家と同じく Java の `List` は `RandomAccess`（`ArrayList`）ならベクター、そうでなければ（`LinkedList`）リスト、`Map` はマップ、`Set` はセットとして、そのコレクション自身の順序で印字されます。それ以外のホストオブジェクトと、`println`/`print` でのすべてのホストオブジェクトは `#<java C>` と表示されます（本家の `#object[...]` からハッシュと `toString` を除いた形）。クラスオブジェクトはクラス名で表示されます。メッセージと cause だけを持つ throwable クラスの構築は例外になり、例外（捕捉した実行時エラーを含む）の `.getMessage`/`.getLocalizedMessage`/`.getCause` はどのバックエンドでもその例外から答えます。メンバが投げた例外はホスト自身のもので、catch はそのクラスで捕捉します（[try](try.md)）。プログラムが作った例外をメンバに渡すと、そのクラスのホストの例外として渡ります（[仕様との差異](../deviations.md)）。Java のインタフェースが期待される位置に渡した fn はその抽象メソッドを実装し、メソッドの引数で呼ばれます。インタフェースの default メソッドは本体を保ちます（[仕様との差異](../deviations.md)）。Java の `false` は `false` として返ります。メンバに渡した値は、Java からは本家自身のオブジェクトと同じように読めるオブジェクトとして届き（コレクションは Clojure の印字どおりに綴られる読み取り専用の `java.util` のコレクション、キーワード・シンボル・分数は本家と同じく等価判定・ハッシュ・順序付けされるオブジェクト、本体が Java のインタフェース（`Runnable`・`Comparable`・`Iterable`・`java.util` のコレクション）を実装するか `Object` のメソッドを上書きする [deftype](deftype.md) や [reify](reify.md) は、それらのインタフェースを実装し、`equals`・`hashCode`・`toString` も含めて自分のメソッドを呼ぶオブジェクト、本体が Java のインタフェースを実装する [record](defrecord.md) はエントリを持ち、そのインタフェースも実装する `java.util.Map`、それ以外の値は自分自身とだけ等しいオブジェクト）、戻るときは元の値になります（[仕様との差異](../deviations.md)）。[バイト配列](byte-array.md)は、メンバが `byte[]` を取る位置（`Object` の引数を含む）にそのバイトの `byte[]` として届き、メンバが書き込んだ値は呼び出しの後でプログラムから読めます。メンバが答えた `byte[]` と、ホストのコレクションが保持する `byte[]` は、バイト配列として戻ります。Java が fn、[proxy](proxy.md)、deftype や reify のメソッドに渡す `byte[]` もバイト配列で、そこへ書き込んだ値は Java から読めます。fn や proxy のメソッドが答えたバイト配列は、その `byte[]` として Java へ渡ります（[仕様との差異](../deviations.md)）。`(String. bytes ...)` と `.getBytes` は、どのバックエンドでも UTF-8、ISO-8859-1、US-ASCII で復号と符号化をします。文字セットは文字列か、`java.nio.charset.StandardCharsets` のフィールド、リテラルの `Charset/forName` で指定でき、後者は専用の文字セット値で、メンバへはホストの `Charset` として渡ります（[byte-array](byte-array.md)）。

| Name | Example | Result |
|---|---|---|
| `.` | `(. "hi" length)` | `2` |
| `..` | `(.. "hi" (toUpperCase) (length))` | `2` |
| `.name / .-name` | `(.toUpperCase "hi")` | `HI` |
| `Class/member` | `(Integer/parseInt "42")` | `42` |
| `Class/member` (値) | `(every? Character/isWhitespace " ")` | `true` |
| `Class/.method` | `(map String/.length ["ab" "abcd"])` | `(2 4)` |
| `Class/new` | `(String/new "q")` | `q` |
| `^[types]` | `(map ^[double] Math/abs [-1 2])` | `(1.0 2.0)` |
| `new` | `(.length (new String "hi"))` | `2` |
| `memfn` | `((memfn toUpperCase) "hi")` | `HI` |
| `proxy` | `(.get (proxy [java.util.function.Supplier] [] (get [] "p")))` | `p` |
| インタフェースとしての fn | `(let [l (java.util.ArrayList. [3 1 2])] (.sort l (fn [a b] (compare b a))) (vec l))` | `[3 2 1]` |
| インタフェースとしての reify | `(let [ran (atom 0)] (doto (Thread. (reify Runnable (run [_] (swap! ran inc)))) .start .join) @ran)` | `1` |
