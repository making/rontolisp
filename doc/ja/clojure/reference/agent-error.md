# agent-error

`(agent-error a)`

失敗状態の agent が止まった原因のエラー（例外を投げたアクション、拒否した validator）を返し、失敗していなければ `nil` を返します。非推奨の `(agent-errors a)` はそれをリストで返し、なければ `nil` です。関数値として動きます。

```clojure
(def a (agent 1 :validator pos?))
(send a dec)
(println @a (ex-message (agent-error a)))
```

```
1 Invalid reference state
```
