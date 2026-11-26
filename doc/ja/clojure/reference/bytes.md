# bytes

`(bytes x)`

オラクルの `bytes` が `byte[]` へキャストするのと同じく、`x` をバイト配列へキャストします。
バイト配列と `nil` はそれ自身を返し、ほかの値はオラクルの `ClassCastException` です。値としては
1 引数の関数です。

```clojure
(def bs (byte-array 2))
(println (identical? bs (bytes bs)) (bytes nil)) ; true nil
```
