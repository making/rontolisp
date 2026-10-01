# cycle

`(cycle coll)`

`coll` の seq ビューを永遠に繰り返す lazy seq を返します。空のコレクションでは `nil` です。`take` 越しにだけ終了します。

```clojure
(println (take 5 (cycle [1 2]))) ; (1 2 1 2 1)
(println (take 10 (cycle [])))   ; nil
```
