# vector

`(vector x...)`

引数をこの順に並べたベクターを返します。ベクターリテラルはこの呼び出しへ低レベル化されます。ベクターのキーは `=` で比較されます（`get` を見てください）。

```clojure
(println (vector 1 2))    ; [1 2]
(println (vector))        ; []
(println (vector :a [1 2])) ; [:a [1 2]]
```
