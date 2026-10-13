# aset-double

`(aset-double array index value)` `(aset-double array index index2 ... value)`

`array` の `index` に、[double](double.md) と同じくキャストした `value` を格納し（配列を破壊的に変更し）、`value` を返します。添字の変換が先で、オラクルのリフレクションが値より先に添字を変換するのと同様です。`nil` はメッセージなしのオラクルの `NullPointerException`、[バイト配列](byte-array.md)は `IllegalArgumentException`（`argument type mismatch`）、ベクタでもバイト配列でもないものは `IllegalArgumentException`（`Argument is not an array`）、両端の外はメッセージなしの `ArrayIndexOutOfBoundsException` です。添字が複数ある場合、中間は [aget](aget.md) と同様に読みます。

配列はここではベクタです。オラクルが要素の型を検査するのに対し、どのベクタにも格納します（[deviations](../deviations.md)）。すべてのバックエンドで動作します。値としては 3 引数以上の関数です。

```clojure
(def asd (double-array [1.0 2.0]))
(println (aset-double asd 0 9) (vec asd)) ; 9 [9.0 2.0]
```
