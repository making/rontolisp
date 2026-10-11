# send-off

`(send-off a f args...)`

`send` と同じです。オラクルではブロッキングするアクションを別プールで走らせますが、ここにはプールがないため両動詞は区別できません。agent を返します。

```clojure
(def a (agent 1))
(println @(send-off a * 6)) ; 6
```
