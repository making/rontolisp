# make-array

`(make-array Class dim ...)`

各次元の大きさを持つ汎用配列を返します。クラスは要素型を表しますが無視され、ここでは配列はすべて汎用で、書かれていない要素は `nil` を読みます。次元が 1 つなら、その長さのベクターになり、複数なら多次元配列になります。クラスは式ではなく名前でなければなりません。

```clojure
(def mk-a (make-array String 3))
(println (alength mk-a))     ; 3
(println (aget mk-a 0))      ; nil
(def mk-b (make-array Long 2 3))
(println (alength mk-b))     ; 2
```
