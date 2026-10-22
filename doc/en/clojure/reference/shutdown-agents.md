# shutdown-agents

`(shutdown-agents)`

Stops the agent pools, answering `nil`. The pool threads are left running here, so
this only marks the intent: `await` still waits for the actions sent so far.

```clojure
(println (shutdown-agents)) ; nil
```
