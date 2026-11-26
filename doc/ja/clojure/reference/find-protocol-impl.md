# find-protocol-impl

`(find-protocol-impl protocol x)`

`x` の実装のマップ。タグの行、なければ `Object` の既定値の行、なければ `nil`。oracle と同じ。プロトコルでないものには `nil` を返す（oracle は `NullPointerException`）。値としては 2 引数を取る。計算で求めたプロトコルはメソッドテーブル自体を読むが、そこに `Object` の既定値はないため、既定値はリテラルのプロトコル名でのみ見える。

```clojure
(defprotocol Sip (sip [x]))
(defrecord Mug [size])
(extend-protocol Sip Mug (sip [x] :drunk))
(println (= (count (find-protocol-impl Sip (->Mug 1))) 1)) ; true
```
