# drop-while

`(drop-while pred coll)`

`coll` の seq ビューのうち truthy な接頭辞を落とした残りを返します（末尾を共有します）。
値としては2引数のラムダです。

```clojure
(println (drop-while neg? [-2 -1 0 1])) ; (0 1)
```
