# concat

`(concat coll...)`

すべての引数の seq ビューを連結したものを返します。各コレクションは型変換されるため、リスト・ベクター・文字列・マップ・セットのすべてが連結できます。`(concat)` は `nil` です。

```clojure
(println (concat '(1 2) '(3 4)))    ; (1 2 3 4)
(println (concat '(1 2) [3 4]))     ; (1 2 3 4)
(println (concat '(1) '(2) #{3}))   ; (1 2 3)
(println (concat))                  ; nil
```
