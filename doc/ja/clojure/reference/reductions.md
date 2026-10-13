# reductions

`(reductions f coll)` / `(reductions f init coll)`

`f` の途中までの畳み込み。`init`（または最初の要素）とそれ以降の各畳み込み結果を並べ、`reduced` で包まれた答えの手前で止まる。oracle と同じ。lazy な入力には lazy seq を、strict な入力には strict なリストを返す。値としては 2 引数か 3 引数を取る。

```clojure
(println (reductions + [1 2 3])) ; (1 3 6)
(println (reductions + 10 [1 2])) ; (10 11 13)
```
