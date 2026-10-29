# shutdown-agents

`(shutdown-agents)`

agent プールを停止し、`nil` を返します。ここでは意図の表明だけです。プールスレッドは待つアクションがなくなると自ら終了し、`await` はそれまでに送られたアクションを待ちます。

```clojure
(println (shutdown-agents)) ; nil
```
