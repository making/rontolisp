# print-method

`(defmethod print-method Type [x writer] body...)` / `(print-method x writer)`

`clojure.core/print-method` はプリンタのマルチメソッドです。プログラムが自分の record や deftype
に定義したメソッドは、`pr`・`prn`・`print`・`println`・`pr-str`・`prn-str`・`print-str`・
`println-str` と REPL がその型の値を書く方法になり、コレクションの中や、コレクションの `str`
の一部でも使われます。writer は書き込み先のストリームです。文字列を `.write` するか、
`binding [*out* writer]` して印字します。`print` の下でメソッド内の `pr` は `print` と同じく
書きます（oracle の `print` と同じく `*print-readably*` が nil になります）。関数として呼ぶと
`print-method` は `x` をその型のメソッドで書き、メソッドがなければ `pr` と同じく書きます。
`remove-method` は既定の書き方に戻し、`get-method` はメソッドを返します（なければ
`print-method` 自身）。

`:type` キーワードのメソッドは、そのキーワードをメタデータに持つ（`(:type (meta x))`）
すべての値を書きます。record や deftype のタグのメソッドより先に使われます。oracle は
`print-method` を `(:type (meta x))` で先にディスパッチするからです。

ホストクラス（`java.util.Date`）のメソッドは、インタプリタと JVM でそのクラスかサブクラスの
ホストオブジェクトを書きます。tools.reader の `#inst` リーダはホストの `Date` を返し、それを
自身のメソッドで印字します。

`defmethod` が計算するディスパッチ値（関数の引数や var）は `defmethod` の実行時に読みます。
record のクラスを受け取る関数（rewrite-clj の `make-printable!`）や `:type` キーワードを
受け取る関数は、そのときその値の下にメソッドを格納します。

プロトコルのインタフェース（ドット付きの名前、`user.Shape`）のメソッドは、本体でそのプロトコルを
実装するすべての record・deftype・`reify` を書きます（拡張では実装になりません）。record の
クラスはプリンタ自身の `clojure.lang.IRecord`・`java.util.Map`・`clojure.lang.IPersistentMap`
のメソッドにも一致するため、oracle と同じく、インタフェースのメソッドが record を書くのは
`prefer-method` でその3つすべてより優先したときだけです。`print-method` の名前の呼び出しでも
値経由でも優先できます（schema の `(doseq [m [print-method ...]] (prefer-method m ...))`）。
一致する2つのメソッドのどちらも優先されていなければ、oracle の `IllegalArgumentException`
（`Multiple methods in multimethod 'print-method' ...`）です。

逸脱: メソッドを持てるのはプログラム自身の record・deftype・プロトコルのインタフェース・
`:type` キーワードと、インスタントか UUID 以外のコアの値が属さないホストクラスです。ここで
作ったインスタントや UUID は自分の印字（`#inst`/`#uuid`。oracle やライブラリのメソッドが書く
内容）のままで、自身のクラスのメソッドがないホストの `Timestamp`・`Calendar`・`UUID` は
ホストオブジェクトとして印字します。`Object`、コアの値が属するクラス（`String`、
`java.util.Map`）、`clojure.lang` のクラス、nil のメソッドは名前で拒否し（計算した値と
ホストクラスは `defmethod` の実行時に `UnsupportedOperationException` として拒否します）、
`print-method` の `methods` も同様です。値そのものの `str` は、oracle がその `toString` を
返すところで、メソッドが書く内容を返します。deftype と `reify` はインタフェースのメソッドの
ほかにプリンタ自身のメソッドに一致しないため、コレクションのインタフェースも実装するものは、
oracle が優先を求めるところでインタフェースのメソッドを使います。優先が行の一部しか決めない
場合、oracle の答えはメソッド表のハッシュ順で決まりますが、ここでは行を固定の順（`IRecord`、
`java.util.Map`、`IPersistentMap`、次にメソッドを最初に格納した順のインタフェース）で
調べます。

```clojure
(defrecord Point [x y])
(defmethod print-method Point [p w] (.write w (str "<" (:x p) "," (:y p) ">")))
(prn (->Point 1 2) [(->Point 3 4)]) ; <1,2> [<3,4>]
(println (pr-str (->Point 5 6))) ; <5,6>

(defprotocol Shape (describe [s]))
(deftype Circle [r] Shape (describe [_] (list 'circle r)))
(defn printable! [iface] (defmethod print-method iface [x w] (print-method (describe x) w)))
(printable! user.Shape)
(prn (Circle. 2) [(Circle. 3)]) ; (circle 2) [(circle 3)]
```
