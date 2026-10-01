# lazy-seq

`(lazy-seq body...)`

lazy seq を返します。本体（暗黙の `do`）は、その seq が最初に realize されるときに実行されます。seq オブジェクトごとに最大1回です。答えが seq の内容になります。`first`/`rest`/`next`/`seq`/`take`/`drop`/`map`/`filter`/`concat` を通じて要素ごとに realize するため、無限 seq も `take` 越しに終了します。take されない本体は実行されません。chunk 化はありません。要素は1つずつ realize します。本体はそれ自体が0引数の `recur` 対象です。本体の末尾位置にある `recur` は thunk 自体を再実行します（引数付きは個数エラー）。

lazy seq の表示はプリンタをハングさせないよう `#<LazySeq>` で拒否されます（lazy な tail は `...` で打ち切られます）。lazy seq は `take` 越しにだけ表示してください。

```clojure
(println (take 5 (lazy-seq (cons 1 (lazy-seq (cons 2 nil)))))) ; (1 2)
(def fibs (lazy-cat [0 1] (map + fibs (rest fibs))))
(println (take 8 fibs)) ; (0 1 1 2 3 5 8 13)
```
