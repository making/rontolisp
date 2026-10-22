# shutdown-agents

`(shutdown-agents)`

agent プールを停止し、`nil` を返します。ここではプールスレッドを走らせたままにするため、意図の表明だけです。`await` はそれまでに送られたアクションを待ちます。

```clojure
(println (shutdown-agents)) ; nil
```
