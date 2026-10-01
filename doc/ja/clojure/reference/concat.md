# concat

`(concat coll...)`

すべての引数を連結して返します。各コレクションは seq ビューを経由して強制されるため、リスト・ベクタ・文字列・マップ・セットを連結できます。`(concat)` は `nil` です。いずれかの引数が lazy の場合、答えは要素ごとに realize される lazy seq になります。そうでない場合は strict に append したリストです。

```clojure
(println (concat '(1 2) '(3 4)))    ; (1 2 3 4)
(println (concat '(1 2) [3 4]))     ; (1 2 3 4)
(println (concat '(1) '(2) #{3}))   ; (1 2 3)
(println (concat))                  ; nil
(println (take 4 (concat [0] (iterate inc 1)))) ; (0 1 2 3)
```
