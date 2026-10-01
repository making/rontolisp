# sort-by

`(sort-by keyfn coll)` / `(sort-by keyfn comp coll)`

`sort` と同じですが、要素ではなくキー化した値で比べます。値としてはキー関数に続けて
1つか2つの引数を取ります。

```clojure
(println (sort-by count ["aaa" "b" "cc"])) ; (b cc aaa)
```
