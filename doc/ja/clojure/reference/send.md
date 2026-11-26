# send

`(send a f args...)`

agent の値と引数に関数 `f` を適用するアクションをキューに入れ、agent を返します。答えは agent の validator を通ります。インタプリタと JVM ではアクションは `send` が戻った後に共有プールで走ります。別のスレッドが走らせている agent はそのスレッドで、先に入ったアクションの後に走らせます。シングルスレッドの WASM レッグでは、アイドルの agent は `send` が戻る前に送信側のスレッドで走らせます。トランザクションやアクションの内側での送信は、それが終わってから走ります。失敗状態の agent は、agent のエラーを cause に持つ `RuntimeException`（`Agent is failed, needs restart`）で拒否します（`restart-agent`）。関数値として動きます。

```clojure
(def a (agent 1))
(send a * 6)
(await a)
(println @a) ; 6
```
