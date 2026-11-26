# create-struct

`(create-struct key...)`

キーから作る構造体定義。`struct` と `struct-map` が読むキーベクトルで、oracle の定義オブジェクトにあたる（ここではキーベクトルとして表示される）。キーは何でもよい。キーなしは oracle の `IllegalArgumentException`（`Must supply keys`）。値としてはキーなし以外の任意個の引数を取る。

```clojure
(println (struct (create-struct :x :y) 1 2)) ; {:x 1, :y 2}
```
