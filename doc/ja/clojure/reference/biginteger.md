# biginteger

`(biginteger x)`

`bigint` と同じです。数値を0方向に切り捨てた整数、または10進文字列を解析した整数を返します。値としては1引数の関数です。

```clojure
(println (biginteger 1.5) (biginteger "12")) ; 1 12
```
