# bytes?

`(bytes? x)`

`clojure.core/bytes?`: バイト配列（[byte-array](byte-array.md)、`.getBytes`、バイトを読む関数が返す
もの）には `true`、ほかの値には `false` を返します。値としては1引数の関数です。

```clojure
(println (bytes? (byte-array 2)) (bytes? [1 2]))  ; true false
```
