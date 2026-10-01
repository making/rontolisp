# seq

`(seq coll)`

`coll` の strict なリストビューを返します。リストはそのまま通り、ベクターか文字列は要素ごとに型変換され、マップはエントリごとに 1 つの 2 要素ベクター、セットは要素ごとに 1 つのメンバーを、いずれも表の走査順（未規定）で与えます。`nil` と `false` は空で、それ以外は oracle と同じくシグナルを上げます。

すべての seq 動詞の空の結果は `nil` です。遅延、チャンキング、メモ化はありません。ビューは strict なリストで、4 つのバックエンドが共有する唯一のシーケンスです。

```clojure
(println (seq '(1 2)))       ; (1 2)
(println (seq [1 2]))        ; (1 2)
(println (seq nil))          ; nil
(println (first {:a 1}))     ; [:a 1]
(println (count (seq "ab"))) ; 2
```
