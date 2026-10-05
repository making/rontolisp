# subs

`(subs s start)`
`(subs s start end)`

`s` の `start` から `end`（デフォルトは長さ）までの部分文字列を返します。core の 2/3 引数形式で、文字列ライブラリとともに列挙されます。値としては 2 または 3 引数のラムダです。

double や比の境界はオラクルと同じく 0 方向へ切り捨てます（`(subs "hello" 1.9)` は `"ello"`）。`.substring` と `.charAt` も同じです。文字列の範囲外の境界は `StringIndexOutOfBoundsException` を投げます。

```clojure
(println (subs "hello" 1 3)) ; el
(println (subs "hello" 1)) ; ello
(println (subs "hello" 1.9)) ; ello
```
