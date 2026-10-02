# re-groups

`(re-groups m)`

マッチャー `m` の最後のマッチをベクターで返します（先頭は全体）。`re-find` のグループあり形と同じです。マッチがなければオラクル同様に `No match found` をシグナルします。関数値としても動きます。

```clojure
(let [m (re-matcher #"(\w+)@(\w+)" "user@host")]
  (println (re-find m))
  (println (re-groups m)))
```
