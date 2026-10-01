# every?

`(every? pred coll)`

`coll` の seq ビューの全要素で `pred` が成り立てば `true`、そうでなければ `false` を返します。
空はオラクル同様 `true` です。値としては2引数のラムダです。

```clojure
(println (every? odd? [1 3])) ; true
(println (every? odd? [1 2])) ; false
```
