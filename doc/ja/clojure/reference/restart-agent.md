# restart-agent

`(restart-agent a v & options)`

失敗状態の agent を再開します。validator が受け入れる必要のある `v` が値になり、エラーが消え、失敗の後ろにキューされたアクションが走ります。`:clear-actions true` ならそれらは捨てられます。`v` を返します。失敗していない agent は拒否されます（`Agent does not need a restart`）。非推奨の `(clear-agent-errors a)` は agent 自身の値で再開します。関数値として動きます。

```clojure
(def a (agent 0))
(send a (fn [_] (throw (Exception. "boom"))))
(println (try (send a inc) (catch RuntimeException e (ex-message e))))
(println (restart-agent a 10))
(send a inc)
(println @a (agent-error a))
```

```
Agent is failed, needs restart
10
11 nil
```
