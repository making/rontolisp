# coll?

`(coll? x)`

リスト・ベクター・マップ・セットで `true` です。`nil`・真偽値・文字列（ランタイム内部では
ベクターですが）・文字・数値はオラクル同様コレクションではありません。値としては
`T`-or-`false` で答える1引数ラムダです。

```clojure
(println (coll? [1]) (coll? nil)) ; true false
```
