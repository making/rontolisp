# extend

`(extend Type Protocol {method fn ...} ...)`

各プロトコルのメソッド関数のマップから、プロトコルの表へ行を足します。
`extend-protocol` が格納するのと同じ行で、実装がすでに値としてある場合に使い
ます。マップリテラルの行は書いたとおりに格納され、プログラムが計算するマップ
（`(assoc clojure.java.io/default-streams-impl ...)`、それを持つ var）はフォームの実行時に
エントリごとに格納されます。プロトコルのメソッドを指さないエントリはオラクル同様に除かれます。
型に `Object` を渡すと `extend-protocol` 同様外れ既定になります。

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
