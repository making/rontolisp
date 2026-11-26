# aset-byte

`(aset-byte array index value)`

バイト配列 `array` の `index` に、[byte](byte.md) と同じくキャストした `value` を格納し
（`-128` から `127` の外の数はオラクルの `IllegalArgumentException`）、`value` を返します。
ほかの配列はオラクルと同じく拒否します。値としては 3 引数の関数です。

```clojure
(def asb (byte-array 2))
(println (aset-byte asb 0 -56) (vec asb)) ; -56 [-56 0]
```
