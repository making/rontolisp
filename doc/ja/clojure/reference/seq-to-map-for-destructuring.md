# seq-to-map-for-destructuring

`(seq-to-map-for-destructuring s)`

キーワード引数 `s` が表すマップを oracle と同様に返します。要素が 2 つ以上ならキーと値のペアの
並びで、同じキーは最後のものが勝ち、奇数個目の最後の要素はマップへの conj と同様に加わります
（マップや `[k v]` ベクターはマージされ、それ以外は `IllegalArgumentException`）。要素が 1 つ
ならその要素自身、0 個なら空マップです。

マップパターンはすべての seq（`seq?`）をこれを通して読むので、`& {:keys [a b]}` は
`:a 1 :b 2` も `{:a 1 :b 2}` も `:a 1 {:b 2}` も受けます。ベクターはそのまま読みます。

```clojure
(prn (seq-to-map-for-destructuring '(:a 1 :b 2 :a 3))) ; {:a 3, :b 2}
(prn (seq-to-map-for-destructuring (list {:a 1})))    ; {:a 1}
(defn opts [x & {:keys [a b] :or {b 9}}] [x a b])
(prn (opts 1 :a 2) (opts 1 {:a 3}) (opts 1 :a 2 {:b 5})) ; [1 2 9] [1 3 9] [1 2 5]
```
