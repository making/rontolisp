# ffirst

`(ffirst coll)`

先頭の先頭です。各レベルは seq ビューを通ります（ベクターの先頭は自身の先頭を読む前に
強制されます）。空の場合は `nil` です。値としては1引数ラムダです。

```clojure
(println (ffirst [[1 2]])) ; 1
(println (ffirst [])) ; nil
```
