# update

`(update m k f args...)`

`k` を `(apply f (get m k) args...)` で書き換えた新しいマップを返します。存在しないキーは
`f` を `nil` に適用します（算術ならオラクル同様シグナルします）。値としてはマップ・キー・関数と
任意の追加引数を取ります。

```clojure
(println (update {:a 1} :a inc)) ; {:a 2}
(println (update {:a 1} :a + 10 20)) ; {:a 31}
```
