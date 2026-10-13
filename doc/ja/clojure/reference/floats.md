# floats

`(floats xs)`

`float[]` へのキャストです。配列 `xs` 自体を返します。`nil` は `nil`、ベクタは [float-array](float-array.md) と同様に広げて返し、それ以外はオラクルの `ClassCastException` です。

配列はここではベクタです。オラクルがプリミティブ配列でないものを拒否するのに対し、ベクタは広げます（[deviations](../deviations.md)）。すべてのバックエンドで動作します。値としては 1 引数の関数です。

```clojure
(prn (vec (floats (float-array [1.0])))) ; [1.0]
(prn (floats nil)) ; nil
```
