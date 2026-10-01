# range

`(range end)` / `(range start end)` / `(range start end step)`

`start`（デフォルト `0`）から `end` 手前（step が負なら手前超え）まで `step`（デフォルト `1`）ずつの等差数列の strict なリストを返します。step が 0 ならシグナルを上げます。リスト全体を組み立てます -- 遅延はありません。

仕様との差異: 終わりのない `(range)` は名前を上げて拒否されます（`infinite range is not supported: range needs an end`）。無限 seq は strict には書けません -- `iterate` を使ってください。

```clojure
(println (range 5))       ; (0 1 2 3 4)
(println (range 2 8))     ; (2 3 4 5 6 7)
(println (range 0 10 3))  ; (0 3 6 9)
(println (range 5 0 -2))  ; (5 3 1)
```
