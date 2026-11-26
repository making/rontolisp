# find

`(find coll key)`

`clojure.core/find`: `coll` が `key` で持つエントリ `[key value]`（2要素のベクター）を返します。
持たなければ `nil` です。マップと record は格納しているキーを返します（キーは `=` で比較するため、
ベクターのキーは `=` なリストのキーを見つけます）。ベクターは範囲内の整数の添字を取り、
`[添字 要素]` を返します。`nil` は `nil` です。それ以外の `coll`（集合、文字列、リスト）は
シグナルを上げます。値としては2引数の関数です。

```clojure
(println (find {:a 1} :a))    ; [:a 1]
(println (find {:a 1} :b))    ; nil
(println (find {:a nil} :a))  ; [:a nil]
(println (find [10 20] 1))    ; [1 20]
```
