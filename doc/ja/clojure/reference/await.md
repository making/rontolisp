# await

`(await a...)`

agent にそれまでに送られたアクションを走らせるか、それを走らせているスレッドを待ち、`nil` を返します。アクションの内側ではオラクル同様シグナルを上げます（`Can't await in agent action`）。

```clojure
(def a (agent 0))
(send a inc)
(println (await a)) ; nil
(println @a) ; 1
```
