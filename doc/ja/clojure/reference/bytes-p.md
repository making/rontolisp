# bytes?

`(bytes? x)`

`clojure.core/bytes?`: どの値にも `false` を返します。ここでは配列が要素のクラスを無視するため、バイト配列という種類がありません。引数は評価されます。値としては1引数の関数です。

```clojure
(println (bytes? [1 2]))  ; false
```
