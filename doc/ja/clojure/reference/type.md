# type

`(type x)`

値のメタデータの `:type` エントリが論理的に真ならそれ（キーワード・文字列など任意の値）を、
そうでなければ [class](class.md) の答えを返します。オラクルの
`(or (get (meta x) :type) (class x))` です。スキーマレジストリの探索はミスすると `(type x)` に
フォールスルーし、クラス値はオラクルと同じく `Pattern` 以外の全エントリをミスするため、
未登録の値は `:malli.core/invalid-schema` で拒否され、一方 `(m/schema #"a")` は正規表現スキーマを
作ります。`defmulti` のディスパッチ関数としては nil に nil を返すため、`nil` のメソッドが
受け取ります。値としては1引数ラムダです。

逸脱: `class` のもの（二つの関数は同じクラス `clojure.lang.AFunction` を答えます。オラクルは
クラスを見分けます）。

```clojure
(defrecord Circle [r])
(println (type (with-meta {:r 1} {:type :circle})) (= (type (->Circle 1)) (class (->Circle 2)))) ; :circle true
(println (type "s") (type nil)) ; java.lang.String nil
(println (= (type #"a") java.util.regex.Pattern)) ; true
(defmulti area type)
(defmethod area :circle [x] (* 3 (:r x) (:r x)))
(defmethod area Circle [x] (* 3 (:r x) (:r x)))
(println (area (with-meta {:r 2} {:type :circle})) (area (->Circle 1))) ; 12 3
```
