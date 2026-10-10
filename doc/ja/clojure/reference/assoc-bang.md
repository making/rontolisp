# assoc!

`(assoc! tr k v & kvs)`

トランジェントのマップの各キー、またはトランジェントのベクターの各インデックスをその場で
設定し、`tr` を返します。最後の値が欠けていれば、オラクルと同じく `nil` です。ベクターの
要素数に等しいインデックスは末尾に追加し、それ以外の範囲外はオラクルの
`IndexOutOfBoundsException`、整数でないキーは `IllegalArgumentException` です。
トランジェントのセットは拒否されます。値としても同じ引数を取ります。

```clojure
(println (persistent! (assoc! (transient {:a 1}) :b 2 :c 3))) ; {:a 1, :b 2, :c 3}
(println (persistent! (assoc! (transient [1 2]) 0 :x 2 :y))) ; [:x 2 :y]
```
