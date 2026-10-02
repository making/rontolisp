# mapcat

`(mapcat f coll...)` / `(mapcat f)`

コレクションに `f` をマップし、マップ結果の seq ビューを strict に結合します
（`nil` 結果は何も寄与しません -- `concat` と同様に nil-safe です）。`(mapcat f)` は
[トランスデューサー](transducers.md)（`(comp (map f) cat)`）です。値としては rest ラムダで、
関数だけを渡すとトランスデューサーを返します。

```clojure
(println (mapcat reverse [[1 2] [3 4]])) ; (2 1 4 3)
(println (mapcat vals [{:a 1} {:b 2}])) ; (1 2)
(println (into [] (mapcat (fn [x] [x x])) [1 2])) ; [1 1 2 2]
```
