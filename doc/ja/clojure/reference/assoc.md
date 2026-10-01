# assoc

`(assoc m k v ...)`

`m` のペアに与えたペアを加えた新しいマップを返します。後のペアが勝ちます。`m` は決して書き換えられません。`nil` への `assoc` は空から始めます。奇数個のペアは拒否されます。ベクターのキーは同一性で比較します。

仕様との差異: ベクターのキーでは オラクルが返す参照がこの処理系では見つかりません。表のキーは同一性で比較するため、`(get (assoc {} [:a] 1) [:a])` は `nil` になります。Transient（`assoc!`）は名前で拒否されます。

```clojure
(def mm-base {:a 1})
(println (get (assoc mm-base :b 2) :b)) ; 2
(println (get mm-base :b))              ; nil
(println (get (assoc nil :a 1) :a))     ; 1
```
