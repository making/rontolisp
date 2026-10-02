# dedupe

`(dedupe coll)`

`coll` の seq から `=` で連続する重複を除いたものを返します（`1` と `1.0` は別で、
ベクターとマップは構造で比べます）。lazy な入力には lazy seq を、strict な入力には strict
なリストを返します。0引数のトランスデューサー形は名前で拒否します。値としては1引数の
関数です。

```clojure
(println (dedupe [1 1 2 2 1 3 3])) ; (1 2 1 3)
(prn (dedupe [[1] [1] [2]])) ; ([1] [2])
```
