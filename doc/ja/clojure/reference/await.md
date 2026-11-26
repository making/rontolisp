# await

`(await a...)`

agent にそれまでに送られたアクションを走らせるか、それを走らせているスレッドを待ち、`nil` を返します。アクションの内側では（`Can't await in agent action`）、失敗状態の agent には `send` と同じく、オラクル同様シグナルを上げます。

```clojure
(def a (agent 0))
(send a inc)
(println (await a)) ; nil
(println @a) ; 1
```
