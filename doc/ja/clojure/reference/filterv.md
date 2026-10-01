# filterv

`(filterv pred coll)`

Clojure の真偽で判定する `filter` の strict・ベクター版です（偽オブジェクトは `nil`
と同様に落ちます）。コレクションはディスパッチャ経由で述語として働きます。値としては
2引数ラムダです。

```clojure
(println (filterv odd? [1 2 3 4])) ; [1 3]
(println (filterv #{:h} [:h :t])) ; [:h]
```
