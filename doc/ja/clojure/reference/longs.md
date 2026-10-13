# longs

`(longs xs)`

`long[]` へのキャストです。配列 `xs` 自体を返します。`nil` は `nil`、ベクタは [long-array](long-array.md) と同様に狭めて返し、それ以外はオラクルの `ClassCastException` です。

配列はここではベクタです。オラクルがプリミティブ配列でないものを拒否するのに対し、ベクタは狭めます（[deviations](../deviations.md)）。すべてのバックエンドで動作します。値としては 1 引数の関数です。

```clojure
(prn (vec (longs (long-array [1 2])))) ; [1 2]
(prn (longs nil)) ; nil
```
