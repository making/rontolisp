# or

`(or expr...)`

フォームを順に評価し、最初の真値を返します。すべて偽値なら最後の値を返します。`nil` と `false` はどちらも偽値なので、`(or nil nil 3)` は両者を飛ばして `3` を返します。

```clojure
(println (or nil nil 3))  ; 3
(println (or false 2))    ; 2
(println (or nil false))  ; false
```
