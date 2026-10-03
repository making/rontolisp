# update-in

`(update-in m keys f args...)`

キーベクターを下るネストした更新結果を返します。欠けた階層は `f` を `nil` に適用します
（算術ならオラクル同様シグナルします）。空のキーベクターは名前付きで拒否されます。ベクターの
階層は [assoc](assoc.md) と同じく添字で進みます。値としては実行時にキー列をたどります。

```clojure
(println (update-in {:a {:b 1}} [:a :b] inc)) ; {:a {:b 2}}
(println (update-in [[0 0] [0 0]] [1 0] inc)) ; [[0 0] [1 0]]
```
