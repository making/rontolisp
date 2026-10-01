# extend

`(extend Type Protocol {method fn ...})`

メソッド関数のマップリテラルからプロトコルの表へ行を足します。
`extend-protocol` が格納するのと同じ行で、実装がすでに値としてある場合に使い
ます。リテラルのマップ以外は拒否されます。型に `Object` を渡すと
`extend-protocol` 同様外れ既定になります。

```clojure
(defprotocol P (m [x]))
(extend String P {:m (fn [s] :str)})
(println (m "s")) ; :str
```
