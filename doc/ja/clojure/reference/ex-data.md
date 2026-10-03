# ex-data

`(ex-data ex)`

`ex-info` のデータマップを返し、それ以外の値には `nil` を返します -- throwable の構築、実行時エラー、throw された文字列やマップはデータを持ちません。`nil` のデータで作った `ex-info` はオラクルと同じく `{}` を返します。関数値として動くため、`map` を渡れます。

```clojure
(println (ex-data (ex-info "m" {:code 7}))) ; {:code 7}
(println (map ex-data [(ex-info "m" 1) "nope"])) ; (1 nil)
```
