# range

`(range)` / `(range end)` / `(range start end)` / `(range start end step)`

終わりがある場合は、`start`（デフォルト `0`）から `end` 手前（step が負なら手前超え）まで
`step`（デフォルト `1`）ずつの等差数列の strict なリストを返します。step が 0 ならシグナルを
上げます。リスト全体を組み立てます -- 遅延はありません。終わりがない場合は oracle と同じく
`0` からの無限の lazy な整数列を返します。`take`・`nth`・`map`・`filter`・`drop`・`reduce`
はこれをたどり、`first` は `0`、`empty?` は false を返します。他の lazy seq と同じく一要素
ずつ実現します（チャンク化はありません）。

```clojure
(println (range 5))       ; (0 1 2 3 4)
(println (range 2 8))     ; (2 3 4 5 6 7)
(println (range 0 10 3))  ; (0 3 6 9)
(println (range 5 0 -2))  ; (5 3 1)
(println (take 3 (range))) ; (0 1 2)
```
