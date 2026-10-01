# distinct

`(distinct coll)`

`coll` の seq ビューから後の重複を落とし、初出を順に残して返します。所属判定は `equal`
です（ベクターはテーブルランタイム同様 identity で比較されます）。値としては1引数のラムダです。

```clojure
(println (distinct [3 1 3 2 1])) ; (3 1 2)
```
