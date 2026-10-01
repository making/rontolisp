# subs

`(subs s start)`
`(subs s start end)`

`s` の `start` から `end`（デフォルトは長さ）までの部分文字列を返します。core の 2/3 引数形式で、文字列ライブラリとともに列挙されます。値としては 2 または 3 引数のラムダです。

```clojure
(println (subs "hello" 1 3)) ; el
(println (subs "hello" 1)) ; ello
```
