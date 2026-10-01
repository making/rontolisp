# mapcat

`(mapcat f coll...)`

コレクションに `f` をマップし、マップ結果の seq ビューを strict に結合します
（`nil` 結果は何も寄与しません -- `concat` と同様に nil-safe です）。単独の関数は
oracle のトランスデューサー形であり、未対応のままです。値としては rest ラムダです。

```clojure
(println (mapcat reverse [[1 2] [3 4]])) ; (2 1 4 3)
(println (mapcat vals [{:a 1} {:b 2}])) ; (1 2)
```
