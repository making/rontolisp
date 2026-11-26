# send-off

`(send-off a f args...)`

`send` と同じです。インタプリタと JVM では共有プールで走ります。オラクルはブロッキングするアクションを専用の無制限プールで走らせますが、ここではブロッキングするアクションがワーカーを占有します。agent を返します。

```clojure
(def a (agent 1))
(send-off a * 6)
(await a)
(println @a) ; 6
```
