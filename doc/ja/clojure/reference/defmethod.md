# defmethod

`(defmethod name dispatch-value [params...] body...)`

メソッドのラムダを multimethod の表へ `dispatch-value` の下に格納します。引数は destructuring し、マップパターンも含まれます。呼び出しでは、ディスパッチ値への完全一致が直接適用されます。そうでなければディスパッチャは、multimethod の階層を通ってその値が降りてくるすべてのメソッドを探します。狭義に最も具体的なものが勝ち、残った同順は `prefer-method` が決め、決着しない同順は `Multiple methods ...` をシグナルします。

ディスパッチ値は `equal` 表のキーと同じく比較されます -- ベクターは同一性なので、ベクターの
ディスパッチ値は階層検索を通じて要素ごとにメソッドへ届きます。ディスパッチ値にはホスト
クラス（`String`、`Number`、`java.util.Map`、`clojure.lang.IPersistentVector`、…）を
書けて、`class` が答えるキーワードの下に格納されるので、`class` の multimethod がそこへ
ディスパッチします。真の nil は `(:C%NIL)` マーカーへ写されます（どの表も
nil をキーにしません）。リテラルの `:nil` ディスパッチ値は `:nil` メソッドにだけ答えます
（オラクル通り）。`Object` は検索の
後・デフォルトの先ですべての値に一致します。

仕様との差異: すべての数値クラス綴りは `:number` にまとまります（オラクルは `Long` と
`Double` を区別します）。ベクターのディスパッチ値は構造的ではなく同一性で比較されます。

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
```
