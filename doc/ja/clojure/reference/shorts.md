# shorts

`(shorts xs)`

`short[]` へのキャストです。配列 `xs` 自体を返します。`nil` は `nil`、ベクタは [short-array](short-array.md) と同様に狭めて返し、それ以外はオラクルの `ClassCastException` です。

配列はここではベクタです。オラクルがプリミティブ配列でないものを拒否するのに対し、ベクタは狭めます（[deviations](../deviations.md)）。すべてのバックエンドで動作します。値としては 1 引数の関数です。

```clojure
(prn (vec (shorts (short-array [1])))) ; [1]
(prn (shorts nil)) ; nil
```
