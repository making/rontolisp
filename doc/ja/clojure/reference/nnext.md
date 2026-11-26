# nnext

`(nnext coll)`

`coll` の `next` の `next` です。各末尾は seq ビューを通り、メンバーが3つ未満なら `nil` です。
値としては1引数ラムダです。

```clojure
(println (nnext [1 2 3])) ; (3)
(println (nnext [1 2])) ; nil
```
