# last

`(last coll)`

`coll` の seq ビューの最後の要素を返し、なければ `nil` です。値としては1引数のラムダです。

```clojure
(println (last [1 2 3])) ; 3
(println (last [])) ; nil
```
