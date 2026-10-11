# set-error-mode!

`(set-error-mode! a m)`

agent のエラーモードを設定し、`nil` を返します。`:continue` では失敗したアクションを飛ばしてキューを進めます。それ以外のモード（`:fail`）はエラーを保持し、`restart-agent` まで agent を止めます。関数値として動きます。

```clojure
(def a (agent 0))
(set-error-mode! a :continue)
(send a (fn [_] (throw (Exception. "boom"))))
(send a inc)
(println @a (agent-error a))
```

```
1 nil
```
