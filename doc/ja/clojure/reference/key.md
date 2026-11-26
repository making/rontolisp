# key

`(key e)`

`clojure.core/key`: マップエントリ `e` のキーを返します。エントリとは、マップの `first`・`seq`
や `find` が返すもの（ここでは2要素のベクター）です。2要素のベクターでないもの（マップ、リスト、
`nil`、数値）は、オラクルと同様にシグナルを上げます。値としては1引数の関数です。

```clojure
(println (key (first {:a 1})))     ; :a
(println (map key {:a 1 :b 2}))    ; (:a :b)
```
