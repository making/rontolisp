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

逸脱: メソッドを持てるのはプログラム自身の record・deftype・`:type` キーワードと、インスタントか
UUID 以外のコアの値が属さないホストクラスです。ここで作ったインスタントや UUID は自分の印字
（`#inst`/`#uuid`。oracle やライブラリのメソッドが書く内容）のままで、自身のクラスのメソッドが
ないホストの `Timestamp`・`Calendar`・`UUID` はホストオブジェクトとして印字します。`Object`、
コアの値が属するクラス（`String`、`java.util.Map`）、`clojure.lang` のクラス、nil のメソッドは
名前で拒否し、`print-method` の `methods` と `prefer-method` も同様です。値そのものの `str` は、
oracle がその `toString` を返すところで、メソッドが書く内容を返します。

```clojure
(defrecord Point [x y])
(defmethod print-method Point [p w] (.write w (str "<" (:x p) "," (:y p) ">")))
(prn (->Point 1 2) [(->Point 3 4)]) ; <1,2> [<3,4>]
(println (pr-str (->Point 5 6))) ; <5,6>
```
