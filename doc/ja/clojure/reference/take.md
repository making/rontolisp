# take

`(take n coll)`

`coll` の seq ビューの最初の `n` 要素のリストを返します。strict で遅延はありません。コレクションより長い take は seq 全体を返します。

Deviation: 長すぎる take は、空のとき seq 全体として `nil` を返します。oracle は `()` を表示します。

```clojure
(println (take 3 (range 10))) ; (0 1 2)
(println (take 2 [1 2 3 4]))  ; (1 2)
(println (take 10 [1 2]))     ; (1 2)
```
