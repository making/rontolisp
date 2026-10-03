# coll?

`(coll? x)`

リスト・lazy seq・ベクター・マップ・セット・レコードで `true` です。`nil`・真偽値・文字列（ランタイム
内部ではベクターですが）・文字・数値・キーワード・atom・var・正規表現パターンはオラクル同様
コレクションではありません。ここでは `nil` が空リストなので、オラクルが `true` を返す
`(coll? ())` は `false` です。値としては `T`-or-`false` で答える1引数ラムダです。

```clojure
(println (coll? [1]) (coll? nil)) ; true false
```
