# and

`(and expr...)`

フォームを順に評価し、最初の偽値を返します。すべて真値なら最後の値を返します。`nil` と `false` はどちらも偽値で、ここのすべてのテストと同様です。

```clojure
(println (and 1 2 3))    ; 3
(println (and 1 nil 3))  ; nil
(println (and 1 false 3)) ; false
```
