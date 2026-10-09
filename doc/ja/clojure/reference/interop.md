# Java interop

interop は java: サーフェスへ低下し、インタプリタと JVM でのみ動きます -- wasm バックエッドは java: を拒否します。クラス名は記されたドット区切り、:import 経由、java.lang 経由（オラクルの既定インポートと同じ一覧で、`IllegalStateException` などの代表的な例外クラスは含まれ、`AutoCloseable` は含まれません）のいずれかで解決されます。文字列レシーバは対応する核操作を返し（Lisp の文字列はホストオブジェクトではない）、ホストオブジェクトでない値の `.toString` は全バックエンドでその `str` の綴りを返します。コレクション・キーワード・シンボル・比・atom は、よく使うメソッド（`.count`・`.get`・`.contains`・`.getName`・`.numerator`・`.deref` など）に全バックエンドで core 関数を通して答え、それ以外のメソッドは名前を挙げて拒否します（[`.name`](dot-name.md)）。[clojure.java.io](clojure-java-io.md) の File・URL・URI・ストリームは全バックエンドでそのクラスのメソッドに答え、Java のメンバーへは表しているホストオブジェクトとして渡ります（インタプリタと JVM）。メンバーが返したホストの `java.io.File`・URL・URI は、`slurp`、`spit`、名前空間の関数、そのクラスに拡張したプロトコルにとって同じ値です。`java.util.Date`・`java.sql.Timestamp`・`java.util.UUID` の構築、`UUID/randomUUID`、`UUID/fromString`、`System/currentTimeMillis` は、全バックエンドで [`#inst` と `#uuid`](instants.md) が読む値を作ります。こうした値はメンバーへホストオブジェクトとして渡り、メンバーが返したホストの Date や UUID はこの値と `=` になります。ホストオブジェクトの `str` はその `toString` を返します。可読な印字（`prn`、`pr-str`、コレクションの中を綴る `str`）では、本家と同じく Java の `List` は `RandomAccess`（`ArrayList`）ならベクター、そうでなければ（`LinkedList`）リスト、`Map` はマップ、`Set` はセットとして、そのコレクション自身の順序で印字されます。それ以外のホストオブジェクトと、`println`/`print` でのすべてのホストオブジェクトは `#<java C>` と表示されます（本家の `#object[...]` からハッシュと `toString` を除いた形）。クラスオブジェクトはクラス名で表示されます。メッセージと cause だけを持つ throwable クラスの構築は例外になり、例外（捕捉した実行時エラーを含む）の `.getMessage`/`.getLocalizedMessage`/`.getCause` はどのバックエンドでもその例外から答えます。メンバが投げた例外はホスト自身のもので、catch はそのクラスで捕捉します（[try](try.md)）。プログラムが作った例外をメンバに渡すと、そのクラスのホストの例外として渡ります（[仕様との差異](../deviations.md)）。Java のインタフェースが期待される位置に渡した fn はその抽象メソッドを実装し、メソッドの引数で呼ばれます。インタフェースの default メソッドは本体を保ちます（[仕様との差異](../deviations.md)）。Java の `false` は `false` として返ります。メンバに渡した値は、Java からは本家自身のオブジェクトと同じように読めるオブジェクトとして届き（コレクションは Clojure の印字どおりに綴られる読み取り専用の `java.util` のコレクション、キーワード・シンボル・分数は本家と同じく等価判定・ハッシュ・順序付けされるオブジェクト、それ以外の値は自分自身とだけ等しいオブジェクト）、戻るときは元の値になります（[仕様との差異](../deviations.md)）。

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
