# unchecked-add

`(unchecked-add a b)`

オーバーフローチェックなしの和を返します。ここの整数は bignum のため桁あふれは起きません。
オラクルが `Long/MAX_VALUE` を超えて折り返すところでも数え続けます。値としては2引数ラムダです。

```clojure
(println (unchecked-add 3 4)) ; 7
```
