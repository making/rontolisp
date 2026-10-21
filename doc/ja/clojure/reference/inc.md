# inc

`(inc x)`

1 大きい数。関数値として動くため、`map`/`filter` に裸のまま渡せます。直接呼び出しは `*unchecked-math*` が真値の間 2^64 で折り返します。関数値はフラグによらず通常の動詞のままです。

```clojure
(println (inc 5)) ; 6
(println (map inc '(1 2))) ; (2 3)
```
