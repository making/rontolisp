# drop-last

`(drop-last coll)` / `(drop-last n coll)`

`coll` の seq から最後の `n` 個（省略時は1個）を除いたものを返します。lazy な入力には
lazy seq を返すので、無限の入力も `take` の下で動きます。strict な入力には strict な
リストを返します（何も残らなければ `nil`、オラクルは `()`）。値としては1引数か2引数を
取り、それ以外はオラクルのアリティエラーをシグナルします。

```clojure
(println (drop-last [1 2 3])) ; (1 2)
(println (drop-last 2 [1 2 3])) ; (1)
(println (take 3 (drop-last (iterate inc 0)))) ; (0 1 2)
```
