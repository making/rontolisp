# some

`(some pred coll)`

`coll` の seq ビューに対する最初の truthy な `(pred x)` を返し、なければ `nil` を返します。
要素そのものではなく述語の値が返ります。値としては2引数のラムダです。

```clojure
(println (some even? [1 3 4])) ; true
(println (some even? [1 3])) ; nil
```
