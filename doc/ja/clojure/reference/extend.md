# extend

`(extend Type Protocol {method fn ...} ...)`

各プロトコルの対象の型の行を、メソッド関数のマップから置換します。
後の `extend` はその型のマップ全体を置換します（オラクル同様です。空マップは空行で
置換し、`nil` は行を除去します）。マップリテラルの行は書いたとおりに格納され、プログラムが計算するマップ
（`(assoc clojure.java.io/default-streams-impl ...)`、それを持つ var）はフォームの実行時に
エントリごとに格納されます。プロトコルのメソッドを指さないエントリはオラクル同様に除かれます。
マップでないものは行をそのまま置き換え、充足と拡張はしますがディスパッチしません
（オラクル同様です）。型に `Object` を渡すと `extend-protocol` 同様外れ既定になります。
本体がそのプロトコルを既に実装している record や deftype への拡張はオラクル同様に
拒否されます。値として
（`(def e extend)` を適用するなど）使う場合も、実行時の型とそのプロトコル・
メソッドマップのペアが同じ行を順に置換し、nil を答えます。

```clojure
(defprotocol P (m [x]))
(extend String P {:m (fn [s] :str)})
(println (m "s")) ; :str

(defprotocol Shape (area [s]) (label [s]))
(def shape-defaults {:label (fn [_] "shape")})
(defrecord Square [n])
(extend Square Shape (assoc shape-defaults :area (fn [s] (* (:n s) (:n s)))))
(println (area (->Square 3)) (label (->Square 3))) ; 9 shape
```
