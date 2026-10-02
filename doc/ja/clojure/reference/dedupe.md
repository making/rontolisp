# dedupe

`(dedupe coll)` / `(dedupe)`

`coll` の seq から `=` で連続する重複を除いたものを返します（`1` と `1.0` は別で、
ベクターとマップは構造で比べます）。lazy な入力には lazy seq を、strict な入力には strict
なリストを返します。`(dedupe)` は[トランスデューサー](transducers.md)です。値としては
0引数か1引数を取ります。

```clojure
(println (dedupe [1 1 2 2 1 3 3])) ; (1 2 1 3)
(prn (dedupe [[1] [1] [2]])) ; ([1] [2])
(println (into [] (dedupe) [1 1 2])) ; [1 2]
```
