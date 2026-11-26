# aget

`(aget array index ...)`

添字の位置にある要素を返します。次元ごとに 1 つの添字が必要で、数が違うか範囲外の添字ならシグナルを上げます。[バイト配列](byte-array.md)の要素は符号付きのバイト（`-128` から `127`）として読みます。

```clojure
(def ag-a (make-array String 2))
(aset ag-a 0 "x")
(println (aget ag-a 0)) ; x
(println (aget ag-a 1)) ; nil
(def ag-m (make-array Long 2 3))
(aset ag-m 1 2 7)
(println (aget ag-m 1 2)) ; 7
(println (aget (byte-array [1 -2]) 1)) ; -2
```
