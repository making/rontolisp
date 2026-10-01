# re-matcher

`(re-matcher pattern s)`

`s` に対する `pattern` のマッチャーを返します。各 `re-find` が最後のマッチの先へ進み（空マッチは1文字進みます。オラクル通り）、`re-groups` が最後のマッチを読み戻します。パターンのみ -- 文字列はオラクル同様にシグナルします。関数値としても動きます。

```clojure
(let [m (re-matcher #"\\w+" "the quick brown fox")]
  (loop [match (re-find m)]
    (when match
      (println match)
      (recur (re-find m)))))
```
