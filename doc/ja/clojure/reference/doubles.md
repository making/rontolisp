# doubles

`(doubles xs)`

`double[]` へのキャストです。配列 `xs` 自体を返します。`nil` は `nil`、ベクタは [double-array](double-array.md) と同様に広げて返し、それ以外はオラクルの `ClassCastException` です。

配列はここではベクタです。オラクルがプリミティブ配列でないものを拒否するのに対し、ベクタは広げます（[deviations](../deviations.md)）。すべてのバックエンドで動作します。値としては 1 引数の関数です。

```clojure
(prn (vec (doubles (double-array [1.0 2.0])))) ; [1.0 2.0]
(prn (doubles nil)) ; nil
```
