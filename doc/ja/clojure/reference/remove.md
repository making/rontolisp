# remove

`(remove pred coll)`

`coll` の seq ビューのうち述語が棄却した要素を順に返します。`filter` の補集合です。
値としては2引数のラムダです。

```clojure
(println (remove odd? [1 2 3 4])) ; (2 4)
```
