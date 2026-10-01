# ex-data

`(ex-data cond)`

`ex-info` のコンディションのデータマップを返し、それ以外のコンディションには `nil` を返します -- throw された文字列やマップはデータスロットを持ちません。関数値として動くため、`map` を渡れます。

```clojure
(println (ex-data (ex-info "m" {:code 7}))) ; {:code 7}
(println (map ex-data [(ex-info "m" 1) "nope"])) ; (1 nil)
```
