# mapcat

`(mapcat f coll...)` / `(mapcat f)`

コレクションに `f` をマップし、マップ結果の seq ビューを結合します（`nil` 結果は何も
寄与しません -- `concat` と同様に nil-safe です）。コレクションが lazy なら lazy に結合し、
マップ結果の各 seq は到達したときに読みます。そうでなければ、マップ結果に lazy な seq が
ない限り strict に結合します。lazy な入力では読んだ分だけを realize します（オラクルは
写像した要素を最大4つ先まで realize します）。`(mapcat f)` は
[トランスデューサー](transducers.md)（`(comp (map f) cat)`）です。値としては rest ラムダで、
関数だけを渡すとトランスデューサーを返します。

```clojure
(println (mapcat reverse [[1 2] [3 4]])) ; (2 1 4 3)
(println (mapcat vals [{:a 1} {:b 2}])) ; (1 2)
(println (into [] (mapcat (fn [x] [x x])) [1 2])) ; [1 1 2 2]
```
