# defmethod

`(defmethod name dispatch-value [params...] body...)`

メソッドのラムダを multimethod の表へ `dispatch-value` の下に格納します。引数は destructuring し、マップパターンも含まれます。呼び出しでは、ディスパッチ値への完全一致が直接適用されます。そうでなければディスパッチャは、multimethod の階層を通ってその値が降りてくるすべてのメソッドを探します。狭義に最も具体的なものが勝ち、残った同順は `prefer-method` が決め、決着しない同順は `Multiple methods ...` をシグナルします。

ディスパッチ値はマップのキーと同じく `=` で比較されるので、ベクターのディスパッチ値も
メソッドへ直接届きます。ディスパッチ値にはホスト
クラス（`String`、`Number`、`java.util.Map`、`clojure.lang.IPersistentVector`、…）を
書けて、`class` が答えるキーワードの下に格納されるので、`class` の multimethod がそこへ
ディスパッチします。throwable のクラス（`IllegalArgumentException`、
`clojure.lang.ExceptionInfo`）やストリームのクラス（`java.io.StringWriter`、
`java.io.Writer`、`java.io.Reader`）は、`class` が例外やストリームに答えるクラス名の
キーワードの下に格納され、検索はオラクルの Java の継承と同じくスーパークラスの連鎖を
たどります。`NumberFormatException` は `Exception` のメソッドより先に
`IllegalArgumentException` のメソッドへ届きます。真の nil は `(:C%NIL)` マーカーへ写されます（どの表も
nil をキーにしません）。リテラルの `:nil` ディスパッチ値は `:nil` メソッドにだけ答えます
（オラクル通り）。`Object` は検索の
後・デフォルトの先ですべての値に一致します。

仕様との差異: すべての数値クラス綴りは `:number` にまとまります（オラクルは `Long` と
`Double` を区別します）。

```clojure
(defmulti m :shape)
(defmethod m :circle [x] 1)
(defmethod m :square [x] 2)
(println (m {:shape :circle})) ; 1

(defmulti m2 class)
(defmethod m2 String [s] (str "str:" s))
(defmethod m2 Number [n] (str "num:" n))
(println (m2 "a")) ; str:a
(println (m2 1)) ; num:1

(defmulti m3 class)
(defmethod m3 IllegalArgumentException [e] :iae)
(defmethod m3 Exception [e] :exception)
(println (m3 (NumberFormatException. "x")) (m3 (ex-info "m" {}))) ; :iae :exception
```
