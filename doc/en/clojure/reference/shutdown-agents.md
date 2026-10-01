# shutdown-agents

`(shutdown-agents)`

Stops the agent pools, answering `nil`. There are no pools here, so this only
marks the intent: sends already ran when sent.

```clojure
(println (shutdown-agents)) ; nil
```
