# find-protocol-method

`(find-protocol-method protocol method x)`

メソッドキーに対する `x` の関数。タグの行のもの、なければ `Object` の既定値のもの、なければ `nil`。oracle と同じ。プロトコルでないものには `nil` を返す（oracle は `NullPointerException`）。値としては 3 引数を取る。計算で求めたプロトコルはメソッドテーブル自体を読むが、そこに `Object` の既定値はないため、既定値はリテラルのプロトコル名でのみ見える。

```clojure
(defprotocol Sip (sip [x]))
(defrecord Mug [size])
(extend-protocol Sip Mug (sip [x] :drunk))
(println ((find-protocol-method Sip :sip (->Mug 1)) (->Mug 1))) ; :drunk
```
