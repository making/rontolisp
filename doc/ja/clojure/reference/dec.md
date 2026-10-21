# dec

`(dec x)`

1 少ない数。`inc` と同じく関数値として動きます。直接呼び出しは `*unchecked-math*` が真値の間 2^64 で折り返します。関数値はフラグによらず通常の動詞のままです。

```clojure
(println (dec 5)) ; 4
```
