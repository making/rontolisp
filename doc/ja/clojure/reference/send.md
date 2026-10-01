# send

`(send a f args...)`

agent の値と引数に関数 `f` を即時に適用し、agent の validator を通して書き込み、agent を返します。同期実行です。非同期の順序付けは対象外のため、後続の `(await a)` は no-op の待ち合わせになります。関数値として動きます。

```clojure
(def a (agent 1))
(println @(send a * 6)) ; 6
```
