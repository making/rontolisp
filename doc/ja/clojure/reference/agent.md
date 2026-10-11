# agent

`(agent v)` / `(agent v :validator validate-fn)`

agent は値を保持し、`send`/`send-off` で送ったアクションが送った順に1つずつその値を更新します。読むのは `deref`/`@` です。スレッドプールはありません。アイドルの agent への送信はそのアクションを送信側のスレッドで走らせてから戻り、別のスレッドが走らせている agent への送信はそのスレッドのキューに加わります。`:validator` は初期値と各アクションの答えを受け入れる必要があります。アクションの実行中 `*agent*` はその agent に束縛され、外側では `nil` です。`future`/`promise` は名前で拒否されたままです。

```clojure
(def a (agent 0))
(send a + 40 2)
(println @a) ; 42
```
