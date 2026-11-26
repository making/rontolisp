# aset

`(aset array index ... value)`

添字の位置に `value` を格納して配列を書き換え、`value` を返します。次元ごとに 1 つの添字が必要です。[バイト配列](byte-array.md)は [byte](byte.md) が返す `-128` から `127` の整数を受け取り、ほかの値はオラクルの `IllegalArgumentException` です。

```clojure
(def as-a (make-array String 2))
(println (aset as-a 0 "x")) ; x
(println (aget as-a 0))     ; x
(def as-m (make-array Long 2 3))
(aset as-m 1 2 7)
(println (aget as-m 1 2))   ; 7
(println (aset (byte-array 1) 0 (byte -5))) ; -5
```
