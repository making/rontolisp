# inc

`(inc x)`

1 大きい数。関数値として動くため、`map`/`filter` に裸のまま渡せます。

```clojure
(println (inc 5)) ; 6
(println (map inc '(1 2))) ; (2 3)
```
