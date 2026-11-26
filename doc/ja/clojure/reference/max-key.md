# max-key

`(max-key k x y...)`

`(k value)` が最大の値を返します。キーが等しければオラクル同様に後のものが勝ちます。
`k` は値ごとに1回走り、値が1個だけなら走りません。キーは数値でなければなりません。
値としては `k` と1個以上の値を取ります。

```clojure
(prn (max-key count "abc" "d" "ef")) ; "abc"
(prn (apply max-key count ["a" "bbb" "cc"])) ; "bbb"
```
