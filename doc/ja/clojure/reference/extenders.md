# extenders

`(extenders protocol)`

プロトコルが拡張されたクラスの seq。oracle と同じ。拡張タグをたどった順に並び、`Object` の既定値は最後の `:object` になる。タグは `class` が答えるキーワード。テーブルを持たない値（プロトコルでないもの）には `nil`（oracle と同じ）。値としては 1 引数を取る。計算で求めたプロトコルはメソッドテーブル自体を読むが、そこに `Object` の既定値はないため、既定値はリテラルのプロトコル名でのみ見える。

```clojure
(defprotocol Sip (sip [x]))
(defrecord Mug [size])
(extend-protocol Sip Mug (sip [x] :drunk))
(println (= (count (extenders Sip)) 1)) ; true
```
