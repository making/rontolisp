# type

`(type x)`

値のメタデータの `:type` エントリが論理的に真ならそれ（キーワード・文字列など任意の値）を、
そうでなければ [class](class.md) の答えを返します。オラクルの
`(or (get (meta x) :type) (class x))` です。`defmulti` のディスパッチ関数としては nil に nil を
返すため、`nil` のメソッドが受け取ります。値としては1引数ラムダです。

逸脱: `:type` エントリがないとき、オラクルはホストクラスを返しますが、ここでは `class` と同じ
種類のキーワードを返します。

```clojure
(defrecord Circle [r])
(println (type (with-meta {:r 1} {:type :circle})) (= (type (->Circle 1)) (class (->Circle 2)))) ; :circle true
(defmulti area type)
(defmethod area :circle [x] (* 3 (:r x) (:r x)))
(defmethod area Circle [x] (* 3 (:r x) (:r x)))
(println (area (with-meta {:r 2} {:type :circle})) (area (->Circle 1))) ; 12 3
```
