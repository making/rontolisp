# shutdown-agents

`(shutdown-agents)`

Stops the agent pools, answering `nil`. Here it only marks the intent: a pool thread
ends by itself once no action waits, and `await` still waits for the actions sent so far.

```clojure
(println (shutdown-agents)) ; nil
```
