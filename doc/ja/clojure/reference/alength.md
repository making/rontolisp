# alength

`(alength array)`

配列の最初の次元の大きさを返します。多次元配列では外側の長さ、[バイト配列](byte-array.md)では要素の数です。

```clojure
(println (alength (make-array String 2)))   ; 2
(println (alength (make-array Long 4 5)))   ; 4
(println (alength (byte-array 3)))          ; 3
```
