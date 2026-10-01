# merge-with

`(merge-with f maps...)`

全マップのペアを1つの新しいマップにまとめ、衝突は旧値と新値への `f` で解決します。
`nil` マップは何も足さず、`(merge-with f)` は `nil` です。最初の非 `nil`
マップが record のときだけオラクル同様 record の型が残ります。値としては関数に続けて任意個の
マップを取ります（record の再ラップも同じです）。

```clojure
(println (merge-with + {:a 1} {:a 2})) ; {:a 3}
```
