# type

`(type x)`

値のメタデータの `:type` エントリが論理的に真ならそれ（キーワード・文字列など任意の値）を、
そうでなければオラクルがホストクラスを返すところではホストクラスを、それ以外は
[class](class.md) の答えを返します。オラクルの `(or (get (meta x) :type) (class x))` です。
関数・キーワード・文字列・シンボル・真偽値・マップ・セットはホストクラスを `(:C%CLASS name)`
指示子で答え、パターンはホストがあるところではホストの `java.util.regex.Pattern` クラス
（ホストがないところではその指示子）で答え、nil には nil を答えます。スキーマレジストリの探索はミスすると `(type x)` に
フォールスルーし、オラクルのクラスは `Pattern` 以外の全エントリをミスするため、未登録の値は
衝突する種類エントリをループ（あるいは誤って型付け）する代わりに、オラクルと同じく
`:malli.core/invalid-schema` で拒否され、一方 `(m/schema #"a")` は正規表現スキーマを
作ります。`class` は種類キーワードのまま、指示子はその
種類としてディスパッチ・`instance?` されます。`defmulti` のディスパッチ関数としては nil に
nil を返すため、`nil` のメソッドが受け取ります。値としては1引数ラムダです。

逸脱: `:type` エントリがないとき、関数・キーワード・文字列・シンボル・真偽値・マップ・セット
はオラクルのホストクラスオブジェクトの代わりに指示子を返し、パターンはホストがあるところでは
ホストクラスオブジェクトそのもの、wasm バックエンドでは指示子を返し、それ以外の値は `class` の種類
キーワードを返します。二つの関数は `=` な指示子を答えます（オラクルはクラスを見分けます）。
マップはサイズ別のクラス名（`PersistentArrayMap`、9エントリ以上は `PersistentHashMap`）、
ソート済みコレクションはツリークラス名で、オラクルのクラスと同じです。

```clojure
(defrecord Circle [r])
(println (type (with-meta {:r 1} {:type :circle})) (= (type (->Circle 1)) (class (->Circle 2)))) ; :circle true
(println (type "s") (type nil)) ; (:C%CLASS java.lang.String) nil
(println (= (type #"a") java.util.regex.Pattern)) ; true
(defmulti area type)
(defmethod area :circle [x] (* 3 (:r x) (:r x)))
(defmethod area Circle [x] (* 3 (:r x) (:r x)))
(println (area (with-meta {:r 2} {:type :circle})) (area (->Circle 1))) ; 12 3
```
