# do

`(do expr...)`

フォームを順に評価し最後の値を返します。フォームがなければ `nil` です。`defn`/`fn`/`let`/`loop`/`when` の本体は、フォームの並びへ暗黙の `do` です。

```clojure
(println (do 1 2 3)) ; 3
```
